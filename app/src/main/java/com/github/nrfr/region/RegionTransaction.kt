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
        /**
         * 实验模式结束时**总是**清理；持久模式仅在失败时清理，成功则保持生效。
         *
         * The apply half and the cleanup half are identical in both modes — only whether cleanup
         * runs on success differs. There is exactly one apply implementation and exactly one
         * teardown implementation.
         */
        mode: TransactionMode = TransactionMode.EXPERIMENT,
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
        var observations: List<String> = emptyList()
        var applySucceeded = false
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
            val touchesNumeric = profile.operatorNumeric != null
            steps += ProbeStep(
                "④ 注册并应用", true,
                "CarrierConfig 下发 ${describeConfigKeys(profile)}；" +
                        if (touchesNumeric)
                            "setCarrierTestOverride 修改 MCC/MNC=$applyNumeric SPN=$applyName（实验性）"
                        else
                            "setCarrierTestOverride 仅用于授予 carrier privileges，" +
                                    "MCC/MNC=$applyNumeric SPN=$applyName 为真实值原样回填（不修改）"
            )
            // Must originate from our own UID, otherwise our cached bundle is not invalidated and
            // the framework replays stale config instead of calling onLoadConfig().
            PrivilegedTelephony.refreshCarrierConfig(subId)

            // ---- 4. wait + verify each requested signal -----------------------------
            effects += verifySignals(context, subId, profile, before)
            during = DiagnosticCollector.collectAll(context, subId)
            observations = observe(profile, before, during)
            steps += ProbeStep(
                "⑤ 验证生效情况", effects.all { it.outcome == SignalOutcome.EFFECTIVE },
                effects.joinToString("; ") { "${it.signal.key}=${it.outcome.label}" }
            )

            // ---- 4b. let the radio settle before judging anything ------------------
            // Changing the SIM operator numeric makes the framework re-evaluate APN selection and
            // can bounce the data connection. Judging connectivity the instant the override lands
            // would report a transient dip as damage.
            if (profile.operatorNumeric != null) {
                val settled = ApnDataSafety.awaitHealthy(context, subId)
                steps += ProbeStep(
                    "④b 等待射频/数据稳定", settled != DataHealth.DEGRADED,
                    "数据状态 = ${settled.label}"
                )
            }

            // ---- 5. side-effect check ----------------------------------------------
            val duringCmp = ReportFormatter.compare(before, during)
            val expected = profile.expectedChangeKeys()
            val side = duringCmp.filter { it.isMutation && it.key !in expected }
            steps += ProbeStep(
                "⑥ 意外副作用检查", side.isEmpty(),
                if (side.isEmpty()) "除预期信号外无变化"
                else "意外变化: ${side.joinToString(", ") { it.key }}"
            )

            // Persistent mode keeps the override only if everything verified AND data is healthy.
            val dataHealthy = ApnDataSafety.awaitHealthy(context, subId) != DataHealth.DEGRADED
            applySucceeded = effects.isNotEmpty() &&
                    effects.all { it.outcome == SignalOutcome.EFFECTIVE } &&
                    side.isEmpty() && dataHealthy
            if (!dataHealthy) {
                steps += ProbeStep(
                    "数据连通性检查", false,
                    "移动数据在稳定后仍不健康，将自动还原"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transaction failed", t)
            steps += ProbeStep("事务异常", false, describe(t))
        } finally {
            // ---- 6-8. restore, release, verify ------------------------------------
            // Persistent mode: on success we deliberately KEEP the override live, and the journal
            // stays open recording ACTIVE so a crash or reboot can still be reconciled.
            val keepActive = mode == TransactionMode.PERSIST && applySucceeded && registered
            cleanup = if (keepActive) {
                TransactionJournal.open(
                    context,
                    journalEntry.copy(
                        state = ProfileState.ACTIVE,
                        appliedCountryIso = profile.countryIso,
                        appliedOperatorName = profile.operatorName,
                        appliedOperatorNumeric = profile.operatorNumeric
                    )
                )
                steps += ProbeStep(
                    "⑦ 保持生效（持久模式）", true,
                    "Profile 已启用并保持；日志记录 ACTIVE，可跨进程/重启对账"
                )
                CleanupReport(
                    identityRestored = true, carrierConfigRestored = true,
                    carrierServiceReleased = true, carrierPrivilegesReleased = true,
                    noUnexpectedChanges = true, apnDataIntact = true,
                    notes = listOf("持久模式：按设计保持生效，未执行清理")
                )
            } else Cleanup.run(
                context = context,
                slot = slot,
                subId = subId,
                baseline = journalEntry,
                before = before,
                registered = registered,
                expectedChangeKeys = emptySet(), // after cleanup NOTHING may differ
                steps = steps
            )
            if (keepActive) {
                // journal intentionally left open
            } else if (cleanup.complete) {
                TransactionJournal.close(context, subId)
                steps += ProbeStep("⑩ 关闭事务日志", true, "清理已验证完整")
            } else {
                steps += ProbeStep(
                    "⑩ 保留事务日志", false,
                    "清理未完成，日志保留以便下次启动时继续回滚：${cleanup.failures().joinToString("; ")}"
                )
            }
        }

        return TransactionResult(
            profile, steps, effects, cleanup,
            applied = registered, observations = observations
        )
    }

    // ------------------------------------------------------------------ verification

    /**
     * 实验期间的显式观察项。
     *
     * Answers, in plain terms, the questions a physical-device tester actually needs: did the
     * value move, did the derived Carrier ID follow, did APN change or merely become unreadable,
     * is data healthy, and — critically — did the *network* side stay put.
     */
    private fun observe(
        profile: RegionalProfile,
        before: List<DiagnosticValue>,
        during: List<DiagnosticValue>
    ): List<String> = buildList {
        fun b(key: String) = before.readable(key)
        fun d(key: String) = during.readable(key)
        fun readable(list: List<DiagnosticValue>, key: String) =
            list.firstOrNull { it.key == key }?.error == null

        profile.operatorNumeric?.let { target ->
            val now = d(Signal.SIM_OPERATOR_NUMERIC.key)
            add("SIM MCC/MNC: ${b(Signal.SIM_OPERATOR_NUMERIC.key)} → ${now ?: "?"} " +
                    if (now == target) "（已变为目标值 $target ✅）" else "（未变为 $target ❌）")

            val idBefore = b(Signal.SIM_CARRIER_ID.key)
            val idAfter = d(Signal.SIM_CARRIER_ID.key)
            add("Carrier ID: $idBefore → ${idAfter ?: "?"} " +
                    if (idBefore != idAfter) "（已改变 —— 预期内，由 MCC/MNC 推导）"
                    else "（未改变）")
        }

        val apnReadableBefore = readable(before, Signal.APN_DATA.key)
        val apnReadableDuring = readable(during, Signal.APN_DATA.key)
        add(
            "APN: " + when {
                !apnReadableBefore && !apnReadableDuring -> "两次均不可读（无法比较）"
                !apnReadableBefore && apnReadableDuring -> "由不可读变为可读，值 = ${d(Signal.APN_DATA.key)}"
                apnReadableBefore && !apnReadableDuring -> "由可读变为不可读（权限差异，非改动）"
                b(Signal.APN_DATA.key) == d(Signal.APN_DATA.key) -> "未变 (${d(Signal.APN_DATA.key)})"
                else -> "${b(Signal.APN_DATA.key)} → ${d(Signal.APN_DATA.key)}（预期内：APN 按运营商代码匹配）"
            }
        )

        add("数据连通性: ${ApnDataSafety.healthOf(during).label}")

        // The whole point of the safety story: the network side must be untouched.
        val netOp = b(Signal.NETWORK_OPERATOR_NUMERIC.key) to d(Signal.NETWORK_OPERATOR_NUMERIC.key)
        val netCo = b(Signal.NETWORK_COUNTRY_ISO.key) to d(Signal.NETWORK_COUNTRY_ISO.key)
        add("网络 MCC/MNC: ${netOp.first} → ${netOp.second} " +
                if (netOp.first == netOp.second) "（未变 ✅ 只读观测）" else "（意外变化 ❌）")
        add("网络国家码: ${netCo.first} → ${netCo.second} " +
                if (netCo.first == netCo.second) "（未变 ✅ 只读观测）" else "（意外变化 ❌）")
    }

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
