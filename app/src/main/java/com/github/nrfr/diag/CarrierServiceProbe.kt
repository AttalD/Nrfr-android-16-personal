package com.github.nrfr.diag

import android.content.Context
import android.util.Log
import com.github.nrfr.manager.PrivilegedTelephony
import java.util.UUID

/**
 * 在**不改变任何真实身份值**的前提下，验证 Android 16 的 CarrierService 机制在本机是否真的可用。
 *
 * ## 为什么这是安全的
 *
 * The probe exercises the full chain — grant carrier privileges → nominate ourselves as the
 * CarrierService → framework binds us → `onLoadConfig()` → config merged and readable — but the
 * bundle we serve contains **only a random sentinel key** (`nrfr_probe_token`). That key means
 * nothing to the framework, so nothing about the SIM, the network, APNs or the radio changes.
 *
 * Three further guards:
 *  - it refuses to run if another package is already the bound CarrierService (taking its place
 *    would drop whatever config *it* supplies, which is not a diagnostic act);
 *  - `setCarrierTestOverride` is called with the SIM's **real** MCC/MNC and SPN, so the two
 *    properties it writes through (`gsm.sim.operator.numeric` / `.alpha`) keep their exact values;
 *  - everything is reverted in a `finally`, and the identity values are re-read afterwards and
 *    diffed, so the report can *prove* nothing moved rather than merely asserting it.
 */
object CarrierServiceProbe {

    private const val TAG = "Nrfr/Probe"
    private const val WAIT_STEP_MS = 250L
    private const val WAIT_TOTAL_MS = 12_000L

    /**
     * @param slot 0-based logical slot index.
     */
    fun run(context: Context, slot: Int, subId: Int): ProbeResult {
        val steps = mutableListOf<ProbeStep>()
        val before = DiagnosticCollector.collectIdentity(context, subId)

        // ---- 1. hidden API availability (lookup only, nothing invoked yet) --------------
        val apiStep = checkApis()
        steps += apiStep
        if (!apiStep.ok) {
            return ProbeResult(steps, false, false)
        }

        // ---- 2. safety precondition ------------------------------------------------------
        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }
        if (existing.isFailure) {
            steps += ProbeStep(
                "查询当前 CarrierService", false,
                existing.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            )
            return ProbeResult(steps, false, false)
        }
        val existingPkg = existing.getOrNull()
        if (!existingPkg.isNullOrBlank() && existingPkg != context.packageName) {
            steps += ProbeStep(
                "安全前置检查", false,
                "已有其它应用被绑定为 CarrierService ($existingPkg)。顶替它会丢失它提供的配置，" +
                        "超出诊断范围，已中止。"
            )
            return ProbeResult(steps, false, false)
        }
        steps += ProbeStep("安全前置检查", true, "当前无其它 CarrierService 绑定")

        // ---- 3. our own signing certificate ---------------------------------------------
        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            steps += ProbeStep("读取本应用签名哈希", false, "无法读取签名证书")
            return ProbeResult(steps, false, false)
        }
        steps += ProbeStep("读取本应用签名哈希", true, "SHA-256 ${certs.first().take(16)}…")

        // ---- 4. capture the SIM's real identity, so we can hand it straight back ---------
        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        steps += ProbeStep(
            "读取 SIM 真实身份", true,
            "MCC+MNC=${realMccMnc ?: "(空)"} SPN=${realSpn ?: "(空)"}（将原样回填）"
        )

        val token = UUID.randomUUID().toString()
        CarrierServiceBridge.reset()
        CarrierServiceBridge.probeToken = token

        var applied = false
        try {
            // ---- 5. grant ourselves carrier privileges ----------------------------------
            val grant = runCatching {
                PrivilegedTelephony.applyCarrierPrivileges(subId, certs.first(), realMccMnc, realSpn)
            }
            steps += ProbeStep(
                "授予 carrier privileges (setCarrierTestOverride)", grant.isSuccess,
                grant.exceptionOrNull()?.let { describe(it) }
            )
            if (grant.isFailure) return finish(context, subId, steps, before)

            // ---- 6. nominate ourselves as the CarrierService ----------------------------
            val nominate = runCatching {
                PrivilegedTelephony.setCarrierServicePackageOverride(
                    subId, context.packageName, context.packageName
                )
            }
            steps += ProbeStep(
                "注册为 CarrierService (setCarrierServicePackageOverride)", nominate.isSuccess,
                nominate.exceptionOrNull()?.let { describe(it) }
            )
            if (nominate.isFailure) return finish(context, subId, steps, before)

            // ---- 7. ask the framework to reload -----------------------------------------
            val notify = runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
            steps += ProbeStep(
                "触发配置重新加载 (notifyConfigChangedForSubId)", notify.isSuccess,
                notify.exceptionOrNull()?.let { describe(it) }
            )

            // ---- 8. wait for the framework to call us back ------------------------------
            val invoked = waitFor { CarrierServiceBridge.invocationCount() > 0 }
            steps += ProbeStep(
                "框架回调 onLoadConfig()", invoked,
                if (invoked) "调用 ${CarrierServiceBridge.invocationCount()} 次, subId=${CarrierServiceBridge.lastSubId}"
                else "等待 ${WAIT_TOTAL_MS / 1000}s 未被回调"
            )

            // ---- 9. verify our bundle actually reached the merged config ----------------
            applied = waitFor { readToken(context, subId) == token }
            steps += ProbeStep(
                "返回的配置已合并生效", applied,
                if (applied) "在 CarrierConfig 中读回哨兵键 $PROBE_LABEL"
                else "未能在 CarrierConfig 中读回哨兵键"
            )

            return finish(context, subId, steps, before, invoked, applied)
        } finally {
            // ---- 10. always revert ------------------------------------------------------
            CarrierServiceBridge.probeToken = null
            runCatching {
                PrivilegedTelephony.setCarrierServicePackageOverride(subId, null, context.packageName)
            }.onFailure { Log.e(TAG, "revert carrierServiceOverride failed", it) }
            runCatching {
                PrivilegedTelephony.clearCarrierPrivileges(subId, realMccMnc, realSpn)
            }.onFailure { Log.e(TAG, "revert carrierPrivileges failed", it) }
            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                .onFailure { Log.e(TAG, "revert notify failed", it) }
        }
    }

    private const val PROBE_LABEL = "nrfr_probe_token"

    private fun finish(
        context: Context,
        subId: Int,
        steps: MutableList<ProbeStep>,
        before: List<DiagnosticValue>,
        invoked: Boolean = false,
        applied: Boolean = false
    ): ProbeResult {
        // Give the revert a moment to land before we compare.
        waitFor(2_000L) { readToken(context, subId) == null }

        val after = DiagnosticCollector.collectIdentity(context, subId)
        val comparisons = ReportFormatter.compare(before, after)
        val mutations = comparisons.filter { it.isMutation }
        val notes = comparisons.filter { it.isNoteworthy }

        steps += ProbeStep(
            "探测后身份值已还原", mutations.isEmpty(),
            if (mutations.isEmpty()) "8 项身份标识与探测前完全一致"
            else "以下身份值发生变化: ${mutations.joinToString(", ") { it.key }}"
        )
        if (notes.isNotEmpty()) {
            // Reported, but explicitly NOT a failure: a field that merely became readable while we
            // briefly held carrier privileges says nothing about the SIM or the network.
            steps += ProbeStep(
                "观测差异（不影响结论）", true,
                notes.joinToString("; ") { ReportFormatter.describe(it) }
            )
        }
        return ProbeResult(steps, invoked, applied, comparisons)
    }

    private fun checkApis(): ProbeStep {
        val missing = mutableListOf<String>()
        val iface = runCatching { Class.forName("com.android.internal.telephony.ITelephony") }
            .getOrElse { return ProbeStep("隐藏接口可用性", false, "找不到 ITelephony: ${it.message}") }
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!

        runCatching { iface.getMethod("setCarrierTestOverride", i, s, s, s, s, s, s, s, s, s) }
            .onFailure { missing += "setCarrierTestOverride" }
        runCatching { iface.getMethod("setCarrierServicePackageOverride", i, s, s) }
            .onFailure { missing += "setCarrierServicePackageOverride" }
        runCatching { iface.getMethod("getCarrierServicePackageNameForLogicalSlot", i) }
            .onFailure { missing += "getCarrierServicePackageNameForLogicalSlot" }

        return ProbeStep(
            "隐藏接口可用性", missing.isEmpty(),
            if (missing.isEmpty()) "ITelephony 三个方法均存在"
            else "本 ROM 缺少: ${missing.joinToString(", ")}"
        )
    }

    private fun readToken(context: Context, subId: Int): String? = runCatching {
        PrivilegedTelephony.currentConfig(subId, context.packageName)
            ?.getString(CarrierServiceBridge.PROBE_KEY)
    }.getOrNull()

    private fun waitFor(totalMs: Long = WAIT_TOTAL_MS, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (condition()) return true
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return condition()
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }
}
