package com.github.nrfr.region

import android.content.Context
import android.util.Log
import com.github.nrfr.diag.*
import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.PrivilegedTelephony

/**
 * 应用一个 [RegionalProfile] 的**事务型**执行器。
 *
 * 这是全项目**唯一**的应用/清理编排点。此前每个实验各自实现清理，导致同一个 bug
 * （先解绑再还原、异步不回读）被复制了好几份。
 *
 * ```
 * 快照 → 校验基线 → 注册 → 应用 → 等待 → 验证 → 分类
 *      → 【仍在注册状态下】还原 → 释放 CarrierService → 验证已解绑
 *      → 最终快照 → 验证清理完整
 * ```
 *
 * ## 不变量
 *
 * 1. **日志先行**：任何特权调用之前先落盘 [OpenTransaction]，因此崩溃/被杀/重启后
 *    [RecoveryManager] 仍能完成回滚。
 * 2. **还原必须在解绑之前**：`UiccProfile.handleSimCountryIsoOverride()` 只在覆盖值非空时才写
 *    属性，所以"移除键"对属性是 no-op；必须在仍是 CarrierService 时把基线值推回去。
 * 3. **异步一律回读**：框架的 `setTestOverride*` 走 handler 线程，绝不认为调用返回即生效。
 * 4. **读不到基线就不开始**：无法还原的实验不允许执行。
 */
object RegionTransaction {

    private const val TAG = "Nrfr/Tx"
    private const val WAIT_STEP_MS = 250L
    private const val WAIT_TOTAL_MS = 15_000L

    fun run(
        context: Context,
        slot: Int,
        subId: Int,
        profile: RegionalProfile,
        /** 提供当前时间，便于测试注入。 */
        nowMillis: Long = System.currentTimeMillis()
    ): TransactionResult {
        val steps = mutableListOf<ProbeStep>()
        val effects = mutableListOf<SignalEffect>()

        // ---- validate the request itself -------------------------------------------
        val problems = profile.validate()
        if (problems.isNotEmpty()) {
            steps += ProbeStep("Profile 校验", false, problems.joinToString("; "))
            return abort(profile, steps)
        }
        if (profile.isEmpty) {
            steps += ProbeStep("Profile 校验", false, "profile 未指定任何要改动的信号")
            return abort(profile, steps)
        }
        steps += ProbeStep("Profile 校验", true, describeProfile(profile))

        // ---- 1. snapshot -----------------------------------------------------------
        val before = DiagnosticCollector.collectAll(context, subId)
        val baselineCountry = before.readable(Signal.SIM_COUNTRY_ISO.key)
        val baselineNumeric = before.readable(Signal.SIM_OPERATOR_NUMERIC.key)
        val baselineName = before.readable(Signal.SIM_OPERATOR_NAME.key)
        val baselineConfigKey = CountryIsoRestore.readConfigCountry(context, subId)
        steps += ProbeStep(
            "① 采集基线快照", true,
            "国家码=${baselineCountry ?: "?"} MCC/MNC=${baselineNumeric ?: "?"} " +
                    "运营商名=${baselineName ?: "?"} 配置键=${baselineConfigKey ?: "(不存在)"}"
        )

        // ---- 2. validate the baseline is restorable --------------------------------
        val missing = buildList {
            if (profile.countryIso != null && baselineCountry == null) add("SIM 国家码")
            if (profile.operatorNumeric != null && baselineNumeric == null) add("SIM MCC/MNC")
        }
        if (missing.isNotEmpty()) {
            steps += ProbeStep(
                "② 基线可还原性检查", false,
                "读不到 ${missing.joinToString("/")}，无法保证可还原，已中止"
            )
            return abort(profile, steps)
        }
        steps += ProbeStep("② 基线可还原性检查", true, "所有将被改动的信号都有可还原的基线")

        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }.getOrNull()
        if (!existing.isNullOrBlank() && existing != context.packageName) {
            steps += ProbeStep("安全前置检查", false, "已有其它 CarrierService ($existing)，已中止")
            return abort(profile, steps)
        }
        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            steps += ProbeStep("读取签名哈希", false, "无法读取本应用签名证书")
            return abort(profile, steps)
        }

        // ---- journal BEFORE touching anything privileged ---------------------------
        val journalEntry = OpenTransaction(
            subId = subId, slot = slot, profileName = profile.name,
            baselineCountryIso = baselineCountry,
            baselineOperatorNumeric = baselineNumeric,
            baselineOperatorName = baselineName,
            baselineConfigCountryKey = baselineConfigKey,
            startedAtMillis = nowMillis
        )
        TransactionJournal.open(context, journalEntry)
        steps += ProbeStep("③ 写入事务日志", true, "崩溃/重启后可自动回滚")

        var registered = false
        var during: List<DiagnosticValue> = emptyList()
        var cleanup: CleanupReport

        try {
            // ---- 3. register --------------------------------------------------------
            // The operator numeric / name we hand to setCarrierTestOverride ARE the MCC/MNC
            // mechanism: there is no CarrierConfig key for them.
            val applyNumeric = profile.operatorNumeric ?: baselineNumeric
            val applyName = profile.operatorName ?: baselineName

            CarrierServiceBridge.reset()
            CarrierServiceBridge.activeProfile = profile

            PrivilegedTelephony.applyCarrierPrivileges(subId, certs.first(), applyNumeric, applyName)
            PrivilegedTelephony.setCarrierServicePackageOverride(
                subId, context.packageName, context.packageName
            )
            registered = true
            steps += ProbeStep(
                "④ 注册并应用", true,
                "CarrierConfig 下发 ${describeConfigKeys(profile)}；" +
                        "setCarrierTestOverride 写 MCC/MNC=$applyNumeric SPN=$applyName"
            )
            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }

            // ---- 4. wait + verify each requested signal -----------------------------
            effects += verifySignals(context, subId, profile, before)
            during = DiagnosticCollector.collectAll(context, subId)
            steps += ProbeStep(
                "⑤ 验证生效情况", effects.all { it.outcome == SignalOutcome.EFFECTIVE },
                effects.joinToString("; ") { "${it.signal.key}=${it.outcome.label}" }
            )

            // ---- 5. side-effect check ----------------------------------------------
            val duringCmp = ReportFormatter.compare(before, during)
            val expected = profile.expectedChangeKeys()
            val side = duringCmp.filter { it.isMutation && it.key !in expected }
            steps += ProbeStep(
                "⑥ 意外副作用检查", side.isEmpty(),
                if (side.isEmpty()) "除预期信号外无变化"
                else "意外变化: ${side.joinToString(", ") { it.key }}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "transaction failed", t)
            steps += ProbeStep("事务异常", false, describe(t))
        } finally {
            // ---- 6-8. restore, release, verify — always ----------------------------
            cleanup = Cleanup.run(
                context = context,
                slot = slot,
                subId = subId,
                baseline = journalEntry,
                before = before,
                registered = registered,
                expectedChangeKeys = emptySet(), // after cleanup NOTHING may differ
                steps = steps
            )
            if (cleanup.complete) {
                TransactionJournal.close(context, subId)
                steps += ProbeStep("⑩ 关闭事务日志", true, "清理已验证完整")
            } else {
                steps += ProbeStep(
                    "⑩ 保留事务日志", false,
                    "清理未完成，日志保留以便下次启动时继续回滚：${cleanup.failures().joinToString("; ")}"
                )
            }
        }

        return TransactionResult(profile, steps, effects, cleanup, applied = registered)
    }

    // ------------------------------------------------------------------ verification

    private fun verifySignals(
        context: Context,
        subId: Int,
        profile: RegionalProfile,
        before: List<DiagnosticValue>
    ): List<SignalEffect> = buildList {
        profile.countryIso?.let { target ->
            val accepted = waitFor {
                CountryIsoRestore.readConfigCountry(context, subId)
                    ?.equals(target, ignoreCase = true) == true
            }
            val effective = waitFor { live(context, subId, Signal.SIM_COUNTRY_ISO.key)?.equals(target, true) == true }
            add(
                SignalEffect(
                    Signal.SIM_COUNTRY_ISO, target,
                    before.readable(Signal.SIM_COUNTRY_ISO.key),
                    live(context, subId, Signal.SIM_COUNTRY_ISO.key),
                    configAccepted = accepted,
                    outcome = classify(accepted, effective)
                )
            )
        }
        profile.operatorName?.let { target ->
            val accepted = waitFor { configHas(context, subId, CarrierConfigKeys.KEY_CARRIER_NAME, target) }
            val effective = waitFor { live(context, subId, Signal.SIM_OPERATOR_NAME.key)?.equals(target, true) == true }
            add(
                SignalEffect(
                    Signal.SIM_OPERATOR_NAME, target,
                    before.readable(Signal.SIM_OPERATOR_NAME.key),
                    live(context, subId, Signal.SIM_OPERATOR_NAME.key),
                    configAccepted = accepted,
                    outcome = classify(accepted, effective)
                )
            )
        }
        profile.operatorNumeric?.let { target ->
            // No CarrierConfig key exists for MCC/MNC, so "accepted" is not a meaningful
            // intermediate state here — only the observable value counts.
            val effective = waitFor { live(context, subId, Signal.SIM_OPERATOR_NUMERIC.key) == target }
            add(
                SignalEffect(
                    Signal.SIM_OPERATOR_NUMERIC, target,
                    before.readable(Signal.SIM_OPERATOR_NUMERIC.key),
                    live(context, subId, Signal.SIM_OPERATOR_NUMERIC.key),
                    configAccepted = null,
                    outcome = if (effective) SignalOutcome.EFFECTIVE else SignalOutcome.REJECTED
                )
            )
        }
    }

    private fun classify(accepted: Boolean, effective: Boolean): SignalOutcome = when {
        effective -> SignalOutcome.EFFECTIVE
        accepted -> SignalOutcome.CONFIG_ACCEPTED_NOT_EFFECTIVE
        else -> SignalOutcome.REJECTED
    }

    private fun configHas(context: Context, subId: Int, key: String, expected: String): Boolean =
        runCatching {
            PrivilegedTelephony.currentConfig(subId, context.packageName)?.getString(key)
                ?.equals(expected, ignoreCase = true) == true
        }.getOrDefault(false)

    private fun live(context: Context, subId: Int, key: String): String? =
        DiagnosticCollector.collectAll(context, subId).readable(key)

    // ---------------------------------------------------------------------- helpers

    private fun abort(profile: RegionalProfile, steps: List<ProbeStep>) = TransactionResult(
        profile = profile,
        steps = steps,
        effects = emptyList(),
        cleanup = CleanupReport(
            identityRestored = true, carrierConfigRestored = true,
            carrierServiceReleased = true, carrierPrivilegesReleased = true,
            noUnexpectedChanges = true, apnDataIntact = true,
            notes = listOf("事务未进入应用阶段，无需清理")
        ),
        applied = false
    )

    private fun describeProfile(p: RegionalProfile) = buildList {
        p.countryIso?.let { add("国家码=$it") }
        p.operatorName?.let { add("运营商名=$it") }
        p.operatorNumeric?.let { add("MCC/MNC=$it（实验性）") }
    }.joinToString(" ")

    private fun describeConfigKeys(p: RegionalProfile) = buildList {
        p.countryIso?.let { add(CarrierConfigKeys.KEY_SIM_COUNTRY_ISO) }
        p.operatorName?.let { add(CarrierConfigKeys.KEY_CARRIER_NAME) }
    }.ifEmpty { listOf("(无)") }.joinToString("+")

    internal fun waitFor(totalMs: Long = WAIT_TOTAL_MS, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (condition()) return true
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return condition()
    }

    internal fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }
}
