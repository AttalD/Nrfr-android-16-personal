package com.github.nrfr.diag

import com.github.nrfr.manager.CarrierConfigKeys

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

    /** 「只改国家码」实验的报告段落。 */
    fun formatCountryExperiment(r: CountryOverrideResult): String = buildString {
        appendLine("===== SIM 国家码覆盖实验（目标: ${r.requestedCountry}）=====")
        for (s in r.steps) {
            appendLine("${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}")
        }
        appendLine()
        appendLine("A. CarrierConfig 是否接受该键: ${if (r.overrideAccepted) "是" else "否"}" +
                " (${CarrierConfigKeys.KEY_SIM_COUNTRY_ISO} = ${r.configKeyValue ?: "未出现"})")
        appendLine("B. getSimCountryIso() 是否改变: ${if (r.simCountryChanged) "是" else "否"}" +
                " (${r.simCountryBefore ?: "?"} → ${r.simCountryDuring ?: "?"})")
        appendLine("C. 网络国家码是否保持: ${if (r.networkCountryHeld) "是" else "否"}")
        appendLine("D. SIM MCC/MNC 是否保持: ${if (r.simOperatorHeld) "是" else "否"}")
        appendLine("   网络 MCC/MNC 是否保持: ${if (r.networkOperatorHeld) "是" else "否"}")
        appendLine("   Carrier ID 是否保持: ${if (r.carrierIdHeld) "是" else "否"}")
        appendLine("   APN 是否保持: ${if (r.apnHeld) "是" else "否"}")
        appendLine("   漫游状态是否保持: ${if (r.roamingHeld) "是" else "否"}")
        appendLine()
        appendLine(">>> 结论: ${r.verdict.label}")
        appendLine(">>> 还原: ${if (r.revertRestored) "已完全还原 ✅" else "存在未还原的值 ❌"}" +
                " (SIM 国家码现为 ${r.simCountryAfter ?: "?"})")
        appendLine(">>> CarrierService 释放: ${if (r.carrierServiceReleased) "已释放 ✅" else "仍被绑定 ❌"}" +
                " (框架报告绑定 = ${r.boundPackageAfter ?: "无"})")
        appendLine(">>> 整体清理: ${if (r.fullyRestored) "干净 ✅" else "不干净 ❌"}")
        if (r.unexpectedSideEffects.isNotEmpty()) {
            appendLine("⚠️ 意外副作用:")
            r.unexpectedSideEffects.forEach { appendLine("  ${describe(it)}") }
        }
        if (r.revertFailures.isNotEmpty()) {
            appendLine("❌ 未还原的值:")
            r.revertFailures.forEach { appendLine("  ${describe(it)}") }
        }
        appendLine()
        appendLine("实验中逐项对比:")
        r.comparisonsDuring.forEach { appendLine("  ${describe(it)}") }
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
