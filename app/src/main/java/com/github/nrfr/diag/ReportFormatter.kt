package com.github.nrfr.diag

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
            appendLine("onLoadConfig 被回调: ${if (p.onLoadConfigInvoked) "是" else "否"}")
            appendLine("返回的配置已生效: ${if (p.configApplied) "是" else "否"}")
            appendLine("探测前后身份值未变: ${if (p.identityUnchanged) "是" else "否"}")
            if (p.changedValues.isNotEmpty()) {
                appendLine("⚠️ 发生变化的值: ${p.changedValues.joinToString(", ")}")
            }
            appendLine("总体结论: ${if (p.succeeded) "机制在本机可用" else "机制在本机不可用"}")
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

    /** 探测前后的身份值对比，返回发生变化的项。 */
    fun diffIdentity(
        before: List<DiagnosticValue>,
        after: List<DiagnosticValue>
    ): List<String> {
        val identitySources = setOf(ValueSource.SIM, ValueSource.NETWORK)
        val beforeMap = before.filter { it.source in identitySources }.associate { it.key to it.value }
        val afterMap = after.filter { it.source in identitySources }.associate { it.key to it.value }
        return (beforeMap.keys + afterMap.keys)
            .filter { beforeMap[it] != afterMap[it] }
            .sorted()
    }
}
