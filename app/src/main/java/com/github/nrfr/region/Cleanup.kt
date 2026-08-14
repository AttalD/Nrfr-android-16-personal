package com.github.nrfr.region

import android.content.Context
import android.util.Log
import com.github.nrfr.diag.*
import com.github.nrfr.manager.PrivilegedTelephony

/**
 * 全项目**唯一**的清理实现。
 *
 * Previously every experiment rolled its own teardown, and the same two defects were duplicated
 * across all of them: unregistering before restoring (which makes the country restore a no-op),
 * and trusting asynchronous framework calls without reading state back. Having exactly one
 * implementation is the point.
 *
 * 顺序是有意义的，不能调换：
 *
 * 1. **仍在注册状态下**把 SIM 国家码推回基线 —— 移除键对属性是 no-op；
 * 2. 把 MCC/MNC 与 SPN 写回真实值（`setCarrierTestOverride` 是直接写属性，可直接还原）；
 * 3. 先撤销 carrier privileges、再清除 CarrierService override，让最后一次重算发生在
 *    我们已不具备资格之后；
 * 4. 轮询验证框架确实不再绑定；
 * 5. 最终快照并逐项核对。
 */
object Cleanup {

    private const val TAG = "Nrfr/Cleanup"

    fun run(
        context: Context,
        slot: Int,
        subId: Int,
        baseline: OpenTransaction,
        /** 事务开始前的完整快照；恢复场景下可为空，届时只做值级核对。 */
        before: List<DiagnosticValue>,
        registered: Boolean,
        expectedChangeKeys: Set<String>,
        steps: MutableList<ProbeStep>
    ): CleanupReport {
        val notes = mutableListOf<String>()

        // ---- 1+2. restore values while the mechanism is still live -----------------
        var identityRestored = true
        if (registered) {
            // Country: must be pushed back through the CarrierService we are still bound to.
            baseline.baselineCountryIso?.let { target ->
                CarrierServiceBridge.activeProfile = null
                val outcome = runCatching {
                    CountryIsoRestore.restore(context, subId, target)
                }.getOrElse { Log.e(TAG, "country restore threw", it); RestoreOutcome.FAILED }
                val ok = outcome != RestoreOutcome.FAILED
                if (!ok) identityRestored = false
                steps += ProbeStep("⑦ 还原 SIM 国家码", ok, "${outcome.label} → 目标 $target")
            }

            // MCC/MNC + SPN: setCarrierTestOverride writes the properties directly, so restoring
            // is just writing the real values back — no push-and-drop dance needed.
            val numericOk = runCatching {
                PrivilegedTelephony.writeSimIdentity(
                    subId,
                    certHashOrNull(context),
                    baseline.baselineOperatorNumeric,
                    baseline.baselineOperatorName
                )
            }
            steps += ProbeStep(
                "⑦ 还原 MCC/MNC 与运营商名", numericOk.isSuccess,
                "MCC/MNC=${baseline.baselineOperatorNumeric ?: "?"} " +
                        "SPN=${baseline.baselineOperatorName ?: "?"}"
            )
            if (numericOk.isFailure) {
                identityRestored = false
                notes += "MCC/MNC 还原调用失败：${numericOk.exceptionOrNull()?.message}"
            }
        }

        // ---- 3+4. release, verified ------------------------------------------------
        val release = runCatching {
            CarrierServiceRelease.release(
                context, slot, subId,
                baseline.baselineOperatorNumeric, baseline.baselineOperatorName
            )
        }.getOrElse {
            Log.e(TAG, "release threw", it)
            ReleaseResult(emptyList(), CarrierServiceRelease.readBound(slot), released = false)
        }
        steps += release.steps
        steps += ProbeStep(
            "⑧ 释放 CarrierService", release.released,
            "框架报告绑定 = ${release.boundPackageAfter ?: "(无)"}"
        )
        CarrierServiceBridge.reset()
        runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }

        // Carrier privileges are dropped as part of release(); verify by checking we are no
        // longer selectable as the carrier service at all.
        val privilegesReleased = release.released

        // ---- 5. final snapshot and verification ------------------------------------
        val after = DiagnosticCollector.collectAll(context, subId)
        val postConfigKey = CountryIsoRestore.readConfigCountry(context, subId)
        val comparisons = if (before.isEmpty()) emptyList()
        else ReportFormatter.compare(before, after)
        val unexpected = comparisons.filter { it.isMutation && it.key !in expectedChangeKeys }

        // Identity is judged on the public-facing getters, not on the config bundle.
        val countryBack = baseline.baselineCountryIso?.let { target ->
            after.readable(Signal.SIM_COUNTRY_ISO.key)?.equals(target, ignoreCase = true) == true
        } ?: true
        val numericBack = baseline.baselineOperatorNumeric?.let { target ->
            after.readable(Signal.SIM_OPERATOR_NUMERIC.key) == target
        } ?: true
        if (!countryBack || !numericBack) identityRestored = false

        val configRestored =
            baseline.baselineConfigCountryKey?.lowercase() == postConfigKey?.lowercase()

        // APN / data safety. Deliberately NOT a snapshot diff of data_state/data_validated:
        // those legitimately flap while telephony reconfigures, and APN becomes unreadable again
        // the moment carrier privileges are dropped. See ApnDataSafety for the reasoning.
        val apnWasChanged = ApnDataSafety.apnChanged(comparisons)
        val baselineHealth = if (before.isEmpty()) DataHealth.UNKNOWN
        else ApnDataSafety.healthOf(before)
        val finalHealth = ApnDataSafety.awaitHealthy(context, subId)
        val apnIntact = !ApnDataSafety.isDamaged(apnWasChanged, baselineHealth, finalHealth)
        steps += ProbeStep(
            "⑨ APN / 数据连通性", apnIntact,
            "APN ${if (apnWasChanged) "被改动" else "未改动"} · " +
                    "数据 ${baselineHealth.label} → ${finalHealth.label}" +
                    if (!apnIntact) "（判定为受损）" else "（正常）"
        )

        steps += ProbeStep(
            "⑨ 最终快照核对", identityRestored && configRestored && unexpected.isEmpty(),
            "国家码=${after.readable(Signal.SIM_COUNTRY_ISO.key) ?: "?"} " +
                    "MCC/MNC=${after.readable(Signal.SIM_OPERATOR_NUMERIC.key) ?: "?"} " +
                    "配置键=${postConfigKey ?: "(不存在)"} " +
                    "APN=${after.readable(Signal.APN_DATA.key) ?: "?"}"
        )

        return CleanupReport(
            identityRestored = identityRestored,
            carrierConfigRestored = configRestored,
            carrierServiceReleased = release.released,
            carrierPrivilegesReleased = privilegesReleased,
            noUnexpectedChanges = unexpected.isEmpty(),
            apnDataIntact = apnIntact,
            unexpectedChanges = unexpected,
            notes = notes
        )
    }

    private fun certHashOrNull(context: Context): String? =
        PrivilegedTelephony.ownCertSha256(context).firstOrNull()
}
