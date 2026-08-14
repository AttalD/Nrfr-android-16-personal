package com.github.nrfr.region

import android.content.Context
import org.json.JSONObject

/**
 * 未完成事务的日志。
 *
 * Written **before** anything privileged is touched and deleted only after cleanup has been
 * verified complete. If the process is killed, the device reboots, or an exception escapes, the
 * record survives and [com.github.nrfr.region.RecoveryManager] finishes the rollback on next
 * start.
 *
 * It stores the *baseline*, not the applied values: recovery needs to know what to restore **to**,
 * and by the time recovery runs the live values are whatever the interrupted run left behind.
 */
data class OpenTransaction(
    val subId: Int,
    val slot: Int,
    val profileName: String,
    /** 事务开始前的真实值，用于还原。 */
    val baselineCountryIso: String?,
    val baselineOperatorNumeric: String?,
    val baselineOperatorName: String?,
    /** 事务开始前 CarrierConfig 中国家码键的状态；null = 原本不存在。 */
    val baselineConfigCountryKey: String?,
    val startedAtMillis: Long,
    /**
     * 持久化 Profile 的生命周期状态；实验型事务始终为 [ProfileState.APPLYING]。
     *
     * Stored in the same record as the baseline so a crash can never leave the state and the
     * values it describes in separate places.
     */
    val state: ProfileState = ProfileState.APPLYING,
    /** 已应用的 profile（持久化模式需要它来判断"当前值是否仍与 profile 一致"）。 */
    val appliedCountryIso: String? = null,
    val appliedOperatorName: String? = null,
    val appliedOperatorNumeric: String? = null
) {
    /** 还原目标 —— 基线一经写入便不再变动。 */
    fun profile(): RegionalProfile = RegionalProfile(
        name = profileName,
        countryIso = appliedCountryIso,
        operatorName = appliedOperatorName,
        operatorNumeric = appliedOperatorNumeric
    )

    fun toJson(): String = JSONObject().apply {
        put("subId", subId)
        put("slot", slot)
        put("profileName", profileName)
        putOpt("baselineCountryIso", baselineCountryIso)
        putOpt("baselineOperatorNumeric", baselineOperatorNumeric)
        putOpt("baselineOperatorName", baselineOperatorName)
        putOpt("baselineConfigCountryKey", baselineConfigCountryKey)
        put("startedAtMillis", startedAtMillis)
        put("state", state.name)
        putOpt("appliedCountryIso", appliedCountryIso)
        putOpt("appliedOperatorName", appliedOperatorName)
        putOpt("appliedOperatorNumeric", appliedOperatorNumeric)
    }.toString()

    companion object {
        fun fromJson(raw: String): OpenTransaction? = runCatching {
            val o = JSONObject(raw)
            OpenTransaction(
                subId = o.getInt("subId"),
                slot = o.getInt("slot"),
                profileName = o.optString("profileName", "?"),
                baselineCountryIso = o.optStringOrNull("baselineCountryIso"),
                baselineOperatorNumeric = o.optStringOrNull("baselineOperatorNumeric"),
                baselineOperatorName = o.optStringOrNull("baselineOperatorName"),
                baselineConfigCountryKey = o.optStringOrNull("baselineConfigCountryKey"),
                startedAtMillis = o.optLong("startedAtMillis", 0L),
                state = runCatching { ProfileState.valueOf(o.optString("state")) }
                    .getOrDefault(ProfileState.APPLYING),
                appliedCountryIso = o.optStringOrNull("appliedCountryIso"),
                appliedOperatorName = o.optStringOrNull("appliedOperatorName"),
                appliedOperatorNumeric = o.optStringOrNull("appliedOperatorNumeric")
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
    }
}

object TransactionJournal {

    private const val PREFS = "nrfr_tx_journal"
    private const val KEY_OPEN = "open_tx_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 写入。
     *
     * Uses `commit()` rather than `apply()` on purpose: the whole point of this record is to
     * survive a process kill that may happen microseconds later, and `apply()` is asynchronous.
     */
    fun open(context: Context, tx: OpenTransaction) {
        prefs(context).edit().putString("$KEY_OPEN${tx.subId}", tx.toJson()).commit()
    }

    /** 读取某个 subId 的日志记录。 */
    fun read(context: Context, subId: Int): OpenTransaction? =
        openTransactions(context).firstOrNull { it.subId == subId }

    /** 只更新状态，**绝不触碰基线** —— 基线一经写入即不可变。 */
    fun updateState(context: Context, subId: Int, state: ProfileState) {
        read(context, subId)?.let { open(context, it.copy(state = state)) }
    }

    fun close(context: Context, subId: Int) {
        prefs(context).edit().remove("$KEY_OPEN$subId").commit()
    }

    fun find(context: Context, subId: Int): OpenTransaction? =
        prefs(context).getString("$KEY_OPEN$subId", null)?.let { OpenTransaction.fromJson(it) }

    /** 所有未闭合的事务（可能来自崩溃或重启前）。 */
    fun openTransactions(context: Context): List<OpenTransaction> =
        prefs(context).all
            .filterKeys { it.startsWith(KEY_OPEN) }
            .values
            .filterIsInstance<String>()
            .mapNotNull { OpenTransaction.fromJson(it) }

    fun hasOpen(context: Context): Boolean = openTransactions(context).isNotEmpty()
}
