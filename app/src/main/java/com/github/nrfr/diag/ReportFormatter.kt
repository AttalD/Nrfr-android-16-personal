package com.github.nrfr.diag

import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.region.SignalCapabilities
import com.github.nrfr.region.TransactionResult

/**
 * 把 [DiagnosticReport] 渲染成可复制/分享的纯文本。
 *
 * Pure logic — no Android dependencies, unit tested on the JVM.
 */
object ReportFormatter {

    fun format(report: DiagnosticReport): String = buildString {
        appendLine("===== Nrfr 电话状态诊断报告 =====")
        appendLine("设备: ${report.device}")
        appendLine("Android: ${report.androidRelease} (API ${report.sdkInt})")
        appendLine("安全补丁: ${report.securityPatch}")
        appendLine("卡槽: ${report.slot}  subId: ${report.subId}")
        appendLine()

        appendLine("--- 当前绑定的 CarrierService ---")
        appendLine(report.existingCarrierServicePackage?.let { "已绑定: $it" } ?: "无（可安全探测）")
        appendLine()

        for (source in ValueSource.entries) {
            val values = report.bySource(source)
            if (values.isEmpty()) continue
            appendLine("--- ${source.label} · ${source.blurb} ---")
            for (v in values) {
                appendLine("${v.label}: ${v.display}")
                appendLine("    来源=${v.source.label}  可改性=${v.mutability.label}  应用可见性=${v.visibility.label}")
            }
            appendLine()
        }

        report.probe?.let { p ->
            appendLine("--- CarrierService 机制探测 ---")
            for (s in p.steps) {
                appendLine("${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}")
            }
            appendLine()
            appendLine("onLoadConfig 被回调: ${if (p.onLoadConfigInvoked) "是" else "否"}")
            appendLine("返回的配置已合并生效: ${if (p.configApplied) "是" else "否"}")
            appendLine(
                ">>> 机制结论: ${
                    if (p.mechanismWorks) "Android 16 CarrierService 方案在本机可用 ✅"
                    else "机制不可用 ❌"
                }"
            )
            appendLine(
                ">>> 还原结论: ${
                    if (p.revertClean) "身份值已完好还原 ✅"
                    else "存在未还原的身份值 ❌"
                }"
            )
            if (p.mutations.isNotEmpty()) {
                appendLine("发生变更的身份值:")
                p.mutations.forEach { appendLine("  ${describe(it)}") }
            }
            if (p.notes.isNotEmpty()) {
                appendLine("观测差异（不影响结论）:")
                p.notes.forEach { appendLine("  ${describe(it)}") }
            }
            appendLine()
            appendLine("身份值逐项对比:")
            p.comparisons.filter { it.isIdentity }.forEach { appendLine("  ${describe(it)}") }
            appendLine()
        } ?: appendLine("--- CarrierService 机制探测: 未执行 ---\n")

        appendLine("--- 本应用能改 / 不能改 ---")
        appendLine("可修改（当前方案）:")
        report.values.filter { it.mutability == Mutability.CHANGEABLE }
            .forEach { appendLine("  • ${it.label}") }
        appendLine("需进阶选项（有风险）:")
        report.values.filter { it.mutability == Mutability.CHANGEABLE_ADVANCED }
            .forEach { appendLine("  • ${it.label}") }
        appendLine("免 root 无法修改:")
        report.values.filter { it.mutability == Mutability.NOT_CHANGEABLE }
            .forEach { appendLine("  • ${it.label}") }
    }

    /**
     * 参与"是否已还原"判定的身份标识集合。
     *
     * Deliberately explicit rather than "everything from the SIM/NETWORK sources": radio state
     * such as `data_network_type` legitimately fluctuates on its own (cell reselection, 5G↔LTE
     * handover) and is not an identity claim, so including it produced false "not reverted"
     * verdicts.
     */
    val IDENTITY_KEYS = setOf(
        "sim_operator",
        "sim_operator_name",
        "sim_country_iso",
        "sim_carrier_id",
        "network_operator",
        "network_operator_name",
        "network_country_iso",
        "network_roaming"
    )

    /**
     * 逐项比较探测前后的值。
     *
     * A read that failed is represented by [DiagnosticValue.error] being non-null — crucially
     * *not* by a null value, because "" and null are legitimate readings. Only a value that was
     * readable both times and differs counts as [ComparisonOutcome.CHANGED].
     */
    fun compare(
        before: List<DiagnosticValue>,
        after: List<DiagnosticValue>
    ): List<ValueComparison> {
        val b = before.associateBy { it.key }
        val a = after.associateBy { it.key }
        return (b.keys + a.keys).sorted().map { key ->
            val bv = b[key]
            val av = a[key]
            val bReadable = bv != null && bv.error == null
            val aReadable = av != null && av.error == null
            val outcome = when {
                !bReadable && !aReadable -> ComparisonOutcome.NOT_COMPARABLE
                !bReadable -> ComparisonOutcome.BECAME_READABLE
                !aReadable -> ComparisonOutcome.BECAME_UNREADABLE
                bv!!.value == av!!.value -> ComparisonOutcome.UNCHANGED
                else -> ComparisonOutcome.CHANGED
            }
            ValueComparison(
                key = key,
                label = (bv ?: av)?.label ?: key,
                outcome = outcome,
                before = bv?.let { if (it.error != null) null else it.value },
                after = av?.let { if (it.error != null) null else it.value },
                isIdentity = key in IDENTITY_KEYS
            )
        }
    }

    /** 事务报告段落。 */
    fun formatTransaction(r: TransactionResult): String = buildString {
        appendLine("===== 地区 Profile 事务：${r.profile.name} =====")
        for (s in r.steps) {
            appendLine("${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}")
        }
        appendLine()
        appendLine("--- 各信号生效情况 ---")
        if (r.effects.isEmpty()) appendLine("(未进入应用阶段)")
        for (e in r.effects) {
            appendLine("${e.signal.label} → 请求 ${e.requested}")
            appendLine("    ${e.before ?: "?"} → ${e.during ?: "?"}  结论=${e.outcome.label}")
            appendLine(
                "    配置是否被接受=" + when (e.configAccepted) {
                    null -> "不适用（该机制不经 CarrierConfig）"
                    true -> "是"
                    false -> "否"
                }
            )
        }
        appendLine()
        if (r.observations.isNotEmpty()) {
            appendLine("--- 实验观察（次生影响与只读核对）---")
            r.observations.forEach { appendLine("  $it") }
            appendLine()
        }
        appendLine("--- 清理完整性（全部条件均须满足）---")
        appendLine("原始身份已还原: ${yn(r.cleanup.identityRestored)}")
        appendLine("CarrierConfig 已还原: ${yn(r.cleanup.carrierConfigRestored)}")
        appendLine("CarrierService 已释放: ${yn(r.cleanup.carrierServiceReleased)}")
        appendLine("carrier privileges 已撤销: ${yn(r.cleanup.carrierPrivilegesReleased)}")
        appendLine("无意外身份变化: ${yn(r.cleanup.noUnexpectedChanges)}")
        appendLine("APN/数据未受损: ${yn(r.cleanup.apnDataIntact)}")
        appendLine(">>> 清理完整: ${if (r.cleanup.complete) "是 ✅" else "否 ❌"}")
        if (r.cleanup.failures().isNotEmpty()) {
            appendLine("未满足: ${r.cleanup.failures().joinToString("; ")}")
        }
        if (r.cleanup.unexpectedChanges.isNotEmpty()) {
            appendLine("意外变化:")
            r.cleanup.unexpectedChanges.forEach { appendLine("  ${describe(it)}") }
        }
        r.cleanup.notes.forEach { appendLine("备注: $it") }
        appendLine()
        appendLine(">>> 事务总体结论: ${if (r.success) "成功 ✅" else "未成功 ❌"}")
        appendLine("    （需同时满足：全部请求信号生效 且 清理完整）")
    }

    private fun yn(b: Boolean) = if (b) "是 ✅" else "否 ❌"

    /** 能力矩阵段落 —— 明确区分已验证 / 实验性 / 免 root 不可改。 */
    fun formatCapabilities(): String = buildString {
        appendLine("===== 信号能力矩阵 =====")
        appendLine("真机验证基准: ${SignalCapabilities.VERIFIED_ON}")
        appendLine()
        for (c in SignalCapabilities.all()) {
            appendLine("${c.signal.label}")
            appendLine("    来源=${c.signal.provenance.label}")
            appendLine("    机制=${c.signal.mechanism.label}")
            appendLine("    应用可见性=${c.signal.appReadable.label}")
            appendLine("    状态=${c.status.label}")
            appendLine("    依据=${c.evidence}")
        }
    }

    /** 单行描述，用于报告与界面。 */
    fun describe(c: ValueComparison): String = when (c.outcome) {
        ComparisonOutcome.CHANGED ->
            "${if (c.isIdentity) "❌" else "ℹ️"} ${c.key}: ${c.before} → ${c.after}" +
                    if (c.isIdentity) "（身份变更）" else "（非身份项，不影响结论）"

        ComparisonOutcome.BECAME_READABLE ->
            "⚠️ ${c.key}: 探测前不可读、探测后可读（值 ${c.after}）；属观测差异，不算变更"

        ComparisonOutcome.BECAME_UNREADABLE ->
            "⚠️ ${c.key}: 探测前可读（值 ${c.before}）、探测后不可读；属观测差异，不算变更"

        ComparisonOutcome.NOT_COMPARABLE ->
            "➖ ${c.key}: 两次均不可读，无法比较"

        ComparisonOutcome.UNCHANGED -> "✅ ${c.key}: 未变"
    }
}
