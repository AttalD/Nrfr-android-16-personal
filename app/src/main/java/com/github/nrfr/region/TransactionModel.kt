package com.github.nrfr.region

import com.github.nrfr.diag.DiagnosticValue
import com.github.nrfr.diag.ProbeStep
import com.github.nrfr.diag.ValueComparison

/**
 * 取出一个可读的值。
 *
 * A failed read is represented by [DiagnosticValue.error], never by a null value — `null` and `""`
 * are both legitimate readings, and conflating them is what made a permission artifact look like
 * an identity mutation.
 */
internal fun List<DiagnosticValue>.readable(key: String): String? =
    firstOrNull { it.key == key }?.takeIf { it.error == null }?.value

/** 单个信号的改动结果。 */
enum class SignalOutcome(val label: String) {
    NOT_ATTEMPTED("未尝试"),

    /** 机制拒绝了：配置键根本没进去，或调用直接失败。 */
    REJECTED("❌ 机制拒绝"),

    /**
     * 配置被接受，但对外可见的值没有改变。
     *
     * The distinction this whole project keeps insisting on: a merged CarrierConfig key proves
     * transport, not effect. An OEM build can accept the key and never act on it.
     */
    CONFIG_ACCEPTED_NOT_EFFECTIVE("⚠️ 已接受但未生效"),

    /** 对外可见的值确实变成了请求值。 */
    EFFECTIVE("✅ 已生效")
}

data class SignalEffect(
    val signal: Signal,
    val requested: String,
    val before: String?,
    val during: String?,
    /** 该机制是否有可回读的配置键；null 表示机制不经 CarrierConfig（如 MCC/MNC）。 */
    val configAccepted: Boolean?,
    val outcome: SignalOutcome
)

/**
 * 清理完成度。**所有**条件都必须为真才算清理干净。
 */
data class CleanupReport(
    /** 原始身份值已还原（以对外可见的 getter 为准）。 */
    val identityRestored: Boolean,
    /** CarrierConfig 回到原始状态（原本不存在的键必须仍不存在）。 */
    val carrierConfigRestored: Boolean,
    /** 框架已不再把本应用绑定为 CarrierService。 */
    val carrierServiceReleased: Boolean,
    /** carrier privileges 已撤销。 */
    val carrierPrivilegesReleased: Boolean,
    /** 除预期改动外没有其它身份值变化。 */
    val noUnexpectedChanges: Boolean,
    /** APN / 数据状态未受损。 */
    val apnDataIntact: Boolean,
    val unexpectedChanges: List<ValueComparison> = emptyList(),
    val notes: List<String> = emptyList()
) {
    val complete: Boolean
        get() = identityRestored && carrierConfigRestored && carrierServiceReleased &&
                carrierPrivilegesReleased && noUnexpectedChanges && apnDataIntact

    /** 未满足的条件，供报告直接列出。 */
    fun failures(): List<String> = buildList {
        if (!identityRestored) add("原始身份未还原")
        if (!carrierConfigRestored) add("CarrierConfig 未回到原始状态")
        if (!carrierServiceReleased) add("CarrierService 仍被绑定")
        if (!carrierPrivilegesReleased) add("carrier privileges 未撤销")
        if (!noUnexpectedChanges) add("存在意外的身份变化")
        if (!apnDataIntact) add("APN / 数据状态发生变化")
    }
}

data class TransactionResult(
    val profile: RegionalProfile,
    val steps: List<ProbeStep>,
    val effects: List<SignalEffect>,
    val cleanup: CleanupReport,
    /** 事务是否真的执行到了"应用"阶段（前置检查失败时为 false）。 */
    val applied: Boolean,
    /**
     * 实验期间的显式观察项（人类可读）。
     *
     * Deliberately separate from [effects]: these are the derived/secondary consequences a tester
     * needs stated in plain terms — Carrier ID drift, APN reselection, data health, and proof the
     * network side did not move.
     */
    val observations: List<String> = emptyList()
) {
    /** 所有被请求改动的信号是否都真正生效。 */
    val allEffective: Boolean
        get() = effects.isNotEmpty() && effects.all { it.outcome == SignalOutcome.EFFECTIVE }

    /**
     * 事务是否成功。
     *
     * 生效**且**清理干净，缺一不可 —— 覆盖成功但没还原干净不算成功。
     */
    val success: Boolean get() = applied && allEffective && cleanup.complete

    fun effect(signal: Signal): SignalEffect? = effects.firstOrNull { it.signal == signal }
}
