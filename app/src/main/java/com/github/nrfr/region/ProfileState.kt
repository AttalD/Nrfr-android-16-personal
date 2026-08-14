package com.github.nrfr.region

/** 持久化 Profile 的生命周期状态。 */
enum class ProfileState(val label: String) {
    /** 未启用，框架处于原始状态。 */
    INACTIVE("未启用"),

    /** 正在应用中（写入日志之后、验证通过之前）。 */
    APPLYING("正在启用…"),

    /**
     * 已启用**且已回读验证**。
     *
     * Never set merely because the framework accepted a request: the values are read back and
     * must match before this state is entered.
     */
    ACTIVE("已启用"),

    /** 正在还原中。 */
    RESTORING("正在恢复…"),

    /**
     * 框架的真实状态与日志不一致，且无法安全地自动判定。
     *
     * Deliberately a terminal-until-user-acts state: guessing here is how a phone ends up
     * half-modified.
     */
    RECOVERY_REQUIRED("需要人工恢复"),

    /** 应用失败且已自动还原到原始状态。 */
    FAILED("启用失败（已自动还原）")
}

/** 对账后应当采取的动作。 */
enum class ReconcileDecision(val label: String) {
    /** 日志与现实一致：确实没有任何覆盖在生效。 */
    NONE_ACTIVE("无覆盖生效"),

    /** 日志与现实一致：覆盖仍然完整生效。 */
    STILL_ACTIVE("覆盖仍然生效"),

    /** 存在残留，需要执行一次完整清理。 */
    NEEDS_CLEANUP("需要执行清理"),

    /** 状态不明确，交给用户决定。 */
    RECOVERY_REQUIRED("状态不明确，需人工确认")
}

/**
 * 用框架的**真实**状态和日志记录做对账。
 *
 * Pure logic so every crash/reboot permutation can be unit tested. The inputs are all read back
 * from the framework — never assumed.
 *
 * The important asymmetry: a reboot wipes every privileged override (both `setCarrierTestOverride`
 * and `setCarrierServicePackageOverride` are in-memory), so "journal says ACTIVE but nothing is
 * applied any more" is the *normal* post-reboot case and must resolve to a clean INACTIVE — not to
 * an error, and not to silently re-applying.
 */
object StateReconciler {

    fun reconcile(
        journalState: ProfileState?,
        boundToUs: Boolean,
        /** 当前可观测值是否与 profile 想要的值一致。 */
        valuesMatchProfile: Boolean,
        /** 当前可观测值是否与记录的基线一致。 */
        valuesMatchBaseline: Boolean
    ): ReconcileDecision = when (journalState) {
        null, ProfileState.INACTIVE ->
            // No record of anything applied. If the framework nonetheless shows us bound or the
            // values do not match the baseline, something leaked and must be cleaned up.
            if (boundToUs) ReconcileDecision.NEEDS_CLEANUP else ReconcileDecision.NONE_ACTIVE

        ProfileState.ACTIVE -> when {
            boundToUs && valuesMatchProfile -> ReconcileDecision.STILL_ACTIVE
            // Reboot cleared the in-memory overrides: nothing to undo, just close the record.
            !boundToUs && valuesMatchBaseline -> ReconcileDecision.NONE_ACTIVE
            // Bound but the values drifted, or unbound while values are still overridden.
            else -> ReconcileDecision.NEEDS_CLEANUP
        }

        // Interrupted mid-flight. If everything already looks like the baseline there is nothing
        // to do; otherwise run the one cleanup path.
        ProfileState.APPLYING, ProfileState.RESTORING -> when {
            !boundToUs && valuesMatchBaseline -> ReconcileDecision.NONE_ACTIVE
            else -> ReconcileDecision.NEEDS_CLEANUP
        }

        ProfileState.RECOVERY_REQUIRED -> ReconcileDecision.RECOVERY_REQUIRED

        ProfileState.FAILED ->
            if (boundToUs || !valuesMatchBaseline) ReconcileDecision.NEEDS_CLEANUP
            else ReconcileDecision.NONE_ACTIVE
    }

    /** 清理之后应落到哪个状态。 */
    fun stateAfterCleanup(cleanupComplete: Boolean): ProfileState =
        if (cleanupComplete) ProfileState.INACTIVE else ProfileState.RECOVERY_REQUIRED

    /** 是否允许发起一次新的应用。 */
    fun canApply(state: ProfileState): Boolean = state == ProfileState.INACTIVE

    /** 是否允许发起还原。 */
    fun canRestore(state: ProfileState): Boolean =
        state == ProfileState.ACTIVE || state == ProfileState.RECOVERY_REQUIRED ||
                state == ProfileState.FAILED
}
