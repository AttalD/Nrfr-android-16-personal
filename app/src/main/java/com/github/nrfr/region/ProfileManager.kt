package com.github.nrfr.region

import android.content.Context
import android.util.Log
import com.github.nrfr.diag.CarrierServiceRelease
import com.github.nrfr.diag.CountryIsoRestore
import com.github.nrfr.diag.DiagnosticCollector
import com.github.nrfr.diag.DiagnosticValue
import com.github.nrfr.diag.ProbeStep

data class LifecycleResult(
    val state: ProfileState,
    val steps: List<ProbeStep>,
    val transaction: TransactionResult? = null,
    val message: String? = null
) {
    val ok: Boolean get() = state == ProfileState.ACTIVE || state == ProfileState.INACTIVE
}

/**
 * 持久化 Region Profile 的生命周期。
 *
 * ## 与实验事务的关系
 *
 * This adds **no new apply or teardown code**. Applying is [RegionTransaction.run] in
 * [TransactionMode.PERSIST]; restoring is the very same [Cleanup] that runs #12/#13 validated.
 * The only thing that lives here is the state machine and the reconciliation against the framework
 * — the parts that make "keep it applied" safe.
 *
 * ## 为什么状态必须回读
 *
 * `ACTIVE` is never entered because a call returned without throwing. The transaction verifies
 * every requested signal by reading it back, checks for unexpected side effects, and waits for
 * mobile data to settle healthy; only then is the journal marked ACTIVE.
 */
object ProfileManager {

    private const val TAG = "Nrfr/Profile"

    // -------------------------------------------------------------------- read

    /** 日志记录的状态（未对账）。 */
    fun recordedState(context: Context, subId: Int): ProfileState =
        TransactionJournal.read(context, subId)?.state ?: ProfileState.INACTIVE

    fun activeProfile(context: Context, subId: Int): RegionalProfile? =
        TransactionJournal.read(context, subId)
            ?.takeIf { it.state == ProfileState.ACTIVE }
            ?.profile()

    fun baseline(context: Context, subId: Int): OpenTransaction? =
        TransactionJournal.read(context, subId)

    // ------------------------------------------------------------------ apply

    /**
     * 启用 profile 并保持生效。
     *
     * Refuses if a profile is already applied — re-applying on top of an active override would
     * capture the *overridden* values as the new baseline and destroy the ability to restore.
     */
    fun apply(
        context: Context,
        slot: Int,
        subId: Int,
        profile: RegionalProfile
    ): LifecycleResult {
        val steps = mutableListOf<ProbeStep>()
        val current = reconcile(context, slot, subId, steps)

        if (!StateReconciler.canApply(current)) {
            steps += ProbeStep(
                "启用前置检查", false,
                "当前状态为 ${current.label}，不能重复启用；请先「恢复原始 SIM」"
            )
            return LifecycleResult(current, steps, message = "当前状态不允许启用")
        }

        TransactionJournal.updateState(context, subId, ProfileState.APPLYING)
        val tx = RegionTransaction.run(context, slot, subId, profile, TransactionMode.PERSIST)
        steps += tx.steps

        // The transaction already rolled itself back if anything failed; trust its own verdict,
        // then confirm against the journal it wrote.
        val ended = TransactionJournal.read(context, subId)?.state
        val state = when {
            ended == ProfileState.ACTIVE -> ProfileState.ACTIVE
            tx.cleanup.complete -> ProfileState.FAILED
            else -> ProfileState.RECOVERY_REQUIRED
        }
        if (state != ProfileState.ACTIVE && state != ProfileState.RECOVERY_REQUIRED) {
            // Failed but cleanly reverted: no record needs to survive.
            TransactionJournal.close(context, subId)
        }
        if (state == ProfileState.RECOVERY_REQUIRED) {
            TransactionJournal.updateState(context, subId, ProfileState.RECOVERY_REQUIRED)
        }

        return LifecycleResult(
            state, steps, tx,
            message = when (state) {
                ProfileState.ACTIVE -> "${profile.name} 已启用"
                ProfileState.FAILED -> "启用失败，已自动还原到原始状态"
                else -> "启用失败且未能完整还原，请执行恢复"
            }
        )
    }

    // ---------------------------------------------------------------- restore

    /**
     * 还原到原始状态。
     *
     * Delegates entirely to [Cleanup] — the same ordering (push values back while still bound,
     * then release, then verify by reading back) proven in runs #12/#13.
     */
    fun restore(context: Context, slot: Int, subId: Int): LifecycleResult {
        val steps = mutableListOf<ProbeStep>()
        val record = TransactionJournal.read(context, subId)
            ?: return LifecycleResult(
                ProfileState.INACTIVE,
                listOf(ProbeStep("无需恢复", true, "没有生效中的 profile")),
                message = "当前没有启用中的 profile"
            )

        TransactionJournal.updateState(context, subId, ProfileState.RESTORING)

        val before = runCatching { DiagnosticCollector.collectAll(context, subId) }
            .getOrDefault(emptyList<DiagnosticValue>())
        val cleanup = Cleanup.run(
            context = context,
            slot = slot,
            subId = subId,
            baseline = record,
            // Compare against the recorded baseline values, not a fresh snapshot: the current
            // values are the overridden ones.
            before = emptyList(),
            registered = true,
            expectedChangeKeys = emptySet(),
            steps = steps
        )

        val state = StateReconciler.stateAfterCleanup(cleanup.complete)
        if (cleanup.complete) {
            TransactionJournal.close(context, subId)
            steps += ProbeStep("关闭 profile 记录", true, "已验证完整还原")
        } else {
            TransactionJournal.updateState(context, subId, ProfileState.RECOVERY_REQUIRED)
            steps += ProbeStep(
                "保留 profile 记录", false,
                "还原未完成：${cleanup.failures().joinToString("; ")}"
            )
        }
        if (before.isEmpty()) Log.d(TAG, "baseline snapshot unavailable; value-level check only")

        return LifecycleResult(
            state, steps,
            message = if (cleanup.complete) "已恢复原始 SIM 身份" else "恢复未完成，请重试或重启"
        )
    }

    // -------------------------------------------------------------- reconcile

    /**
     * 用框架真实状态对账，返回可信的当前状态。
     *
     * Called on app start, on boot, and before any apply/restore. Reads the framework rather than
     * trusting the record — and when the two genuinely disagree, prefers
     * [ProfileState.RECOVERY_REQUIRED] over guessing.
     */
    fun reconcile(
        context: Context,
        slot: Int,
        subId: Int,
        steps: MutableList<ProbeStep> = mutableListOf()
    ): ProfileState {
        val record = TransactionJournal.read(context, subId)
        val bound = CarrierServiceRelease.readBound(slot) == context.packageName
        val values = runCatching { DiagnosticCollector.collectAll(context, subId) }
            .getOrDefault(emptyList())

        fun v(key: String) = values.firstOrNull { it.key == key }?.takeIf { it.error == null }?.value
        val country = v(Signal.SIM_COUNTRY_ISO.key)
        val numeric = v(Signal.SIM_OPERATOR_NUMERIC.key)

        val profile = record?.profile()
        val matchesProfile = profile != null &&
                (profile.countryIso == null || profile.countryIso.equals(country, true)) &&
                (profile.operatorNumeric == null || profile.operatorNumeric == numeric)
        val matchesBaseline = record != null &&
                (record.baselineCountryIso == null || record.baselineCountryIso.equals(country, true)) &&
                (record.baselineOperatorNumeric == null || record.baselineOperatorNumeric == numeric)

        val decision = StateReconciler.reconcile(
            journalState = record?.state,
            boundToUs = bound,
            valuesMatchProfile = matchesProfile,
            valuesMatchBaseline = matchesBaseline
        )
        steps += ProbeStep(
            "状态对账", true,
            "记录=${record?.state?.label ?: "无"} · 绑定=${if (bound) "本应用" else "无"} · " +
                    "国家码=$country MCC/MNC=$numeric → ${decision.label}"
        )

        return when (decision) {
            ReconcileDecision.NONE_ACTIVE -> {
                if (record != null) TransactionJournal.close(context, subId)
                ProfileState.INACTIVE
            }

            ReconcileDecision.STILL_ACTIVE -> ProfileState.ACTIVE

            ReconcileDecision.NEEDS_CLEANUP -> {
                val r = restore(context, slot, subId)
                steps += r.steps
                r.state
            }

            ReconcileDecision.RECOVERY_REQUIRED -> ProfileState.RECOVERY_REQUIRED
        }
    }

    /** 只读观测：SIM 侧与网络侧分别是什么。 */
    fun snapshot(context: Context, subId: Int): Map<String, String?> {
        val values = runCatching { DiagnosticCollector.collectAll(context, subId) }
            .getOrDefault(emptyList())
        fun v(key: String) = values.firstOrNull { it.key == key }
            ?.takeIf { it.error == null }?.value
        return mapOf(
            "sim_country" to v(Signal.SIM_COUNTRY_ISO.key),
            "sim_operator_name" to v(Signal.SIM_OPERATOR_NAME.key),
            "sim_operator" to v(Signal.SIM_OPERATOR_NUMERIC.key),
            "sim_carrier_id" to v(Signal.SIM_CARRIER_ID.key),
            "network_operator" to v(Signal.NETWORK_OPERATOR_NUMERIC.key),
            "network_country" to v(Signal.NETWORK_COUNTRY_ISO.key),
            "data_health" to ApnDataSafety.healthOf(values).label,
            "carrier_config_country" to runCatching {
                CountryIsoRestore.readConfigCountry(context, subId)
            }.getOrNull()
        )
    }
}
