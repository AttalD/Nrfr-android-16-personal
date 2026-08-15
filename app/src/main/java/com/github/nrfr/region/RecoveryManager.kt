package com.github.nrfr.region

import android.content.Context
import android.util.Log
import com.github.nrfr.diag.CarrierServiceRelease
import com.github.nrfr.diag.DiagnosticCollector
import com.github.nrfr.diag.ProbeStep

data class RecoveryOutcome(
    val transaction: OpenTransaction,
    val steps: List<ProbeStep>,
    val cleanup: CleanupReport
) {
    val recovered: Boolean get() = cleanup.complete
}

/**
 * 崩溃 / 进程被杀 / 重启后的回滚补完。
 *
 * [RegionTransaction] 会在动任何特权状态**之前**落盘 [OpenTransaction]，只有在清理被验证完整后
 * 才删除。因此只要还能读到日志，就说明上一次事务没有干净收尾，需要在这里补完。
 *
 * ## 重启后的情况
 *
 * 重启会让两个特权调用（`setCarrierTestOverride` / `setCarrierServicePackageOverride`）
 * 自然失效，SIM 国家码也会由 `SIMRecords.onAllRecordsLoaded()` 从 IMSI 重新解析。
 * 所以重启本身通常已经把设备恢复原状 —— 此处的核对会确认这一点并关闭日志，
 * 而不是盲目再写一遍。
 *
 * 幂等：重复运行安全，已经干净时不会做任何特权调用。
 */
object RecoveryManager {

    private const val TAG = "Nrfr/Recovery"

    /**
     * 是否存在**真正孤立**的事务需要补完回滚。
     *
     * A persistent profile in [ProfileState.ACTIVE] is an open journal entry by design — that is
     * how a reboot or process death can still be reconciled. It is emphatically *not* an
     * interrupted experiment, so it must never raise the recovery banner or be offered a
     * "finish the rollback" action. Only genuinely orphaned records qualify.
     */
    fun hasPendingWork(context: Context): Boolean = orphaned(context).isNotEmpty()

    /** 孤立的事务记录：ACTIVE 属于正常持久状态，排除在外。 */
    fun orphaned(context: Context): List<OpenTransaction> =
        TransactionJournal.openTransactions(context)
            .filter { it.state != ProfileState.ACTIVE }

    /** 处理所有**孤立**的事务。 */
    fun recoverAll(context: Context): List<RecoveryOutcome> =
        orphaned(context).map { recover(context, it) }

    fun recover(context: Context, tx: OpenTransaction): RecoveryOutcome {
        val steps = mutableListOf<ProbeStep>()
        steps += ProbeStep(
            "发现未完成事务", true,
            "profile=${tx.profileName} subId=${tx.subId}，将补完回滚"
        )

        val current = DiagnosticCollector.collectAll(context, tx.subId)
        val bound = CarrierServiceRelease.readBound(tx.slot)
        val stillOurs = bound == context.packageName

        val countryOk = tx.baselineCountryIso?.let { target ->
            current.firstOrNull { it.key == Signal.SIM_COUNTRY_ISO.key }
                ?.takeIf { it.error == null }?.value
                ?.equals(target, ignoreCase = true) == true
        } ?: true
        val numericOk = tx.baselineOperatorNumeric?.let { target ->
            current.firstOrNull { it.key == Signal.SIM_OPERATOR_NUMERIC.key }
                ?.takeIf { it.error == null }?.value == target
        } ?: true

        // Nothing to undo — most commonly because a reboot already reset the in-memory state.
        if (countryOk && numericOk && !stillOurs) {
            steps += ProbeStep(
                "无需回滚", true,
                "身份值已与基线一致且无 CarrierService 绑定（重启通常已自然恢复）"
            )
            TransactionJournal.close(context, tx.subId)
            return RecoveryOutcome(
                tx, steps,
                CleanupReport(
                    identityRestored = true, carrierConfigRestored = true,
                    carrierServiceReleased = true, carrierPrivilegesReleased = true,
                    noUnexpectedChanges = true, apnDataIntact = true,
                    notes = listOf("恢复时已是干净状态")
                )
            )
        }

        steps += ProbeStep(
            "需要回滚", true,
            "国家码一致=$countryOk MCC/MNC一致=$numericOk 仍被绑定=$stillOurs"
        )

        // Re-register only if we must push the country back — that restore requires being the
        // bound CarrierService, and after a reboot we no longer are.
        // countryOk is `true` whenever there is no country baseline, so !countryOk already
        // implies baselineCountryIso != null.
        val needsReregister = !countryOk
        var registered = false
        if (needsReregister) {
            val certs = com.github.nrfr.manager.PrivilegedTelephony.ownCertSha256(context)
            registered = runCatching {
                com.github.nrfr.manager.PrivilegedTelephony.applyCarrierPrivileges(
                    subId = tx.subId,
                    certSha256Hex = certs.first(),
                    realMccMnc = tx.baselineOperatorNumeric,
                    realSpn = tx.baselineOperatorName
                )
                com.github.nrfr.manager.PrivilegedTelephony.setCarrierServicePackageOverride(
                    tx.subId, context.packageName, context.packageName
                )
                true
            }.getOrElse {
                Log.e(TAG, "re-register for recovery failed", it)
                steps += ProbeStep("为回滚重新注册", false, RegionTransaction.describe(it))
                false
            }
            if (registered) steps += ProbeStep("为回滚重新注册", true, "以便把国家码推回基线")
        } else if (stillOurs) {
            // Already bound; the release step below will unwind it.
            registered = true
        }

        val cleanup = Cleanup.run(
            context = context,
            slot = tx.slot,
            subId = tx.subId,
            baseline = tx,
            before = emptyList(), // no pre-transaction snapshot survives a process death
            registered = registered,
            expectedChangeKeys = emptySet(),
            steps = steps
        )

        if (cleanup.complete) {
            TransactionJournal.close(context, tx.subId)
            steps += ProbeStep("回滚完成", true, "事务日志已关闭")
        } else {
            steps += ProbeStep(
                "回滚未完成", false,
                cleanup.failures().joinToString("; ") + "；日志保留，下次启动会再试"
            )
        }
        return RecoveryOutcome(tx, steps, cleanup)
    }
}
