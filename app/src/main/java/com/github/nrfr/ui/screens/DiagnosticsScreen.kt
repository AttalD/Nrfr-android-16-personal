package com.github.nrfr.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.github.nrfr.diag.*
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.model.SimCardInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 只读诊断界面。
 *
 * Phase A (baseline) runs automatically and mutates nothing. Phase B (the CarrierService probe)
 * is behind an explicit confirmation, is refused outright when another carrier app is bound, and
 * reverts itself — see [CarrierServiceProbe].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val simCards: List<SimCardInfo> = remember(context) { CarrierConfigManager.getSimCards(context) }
    var selected by remember { mutableStateOf(simCards.firstOrNull()) }

    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var confirmProbe by remember { mutableStateOf(false) }
    var confirmExperiment by remember { mutableStateOf(false) }
    var experimentCountry by remember { mutableStateOf(CountryOverrideExperiment.DEFAULT_TEST_COUNTRY) }
    var experiment by remember { mutableStateOf<CountryOverrideResult?>(null) }

    fun collect(sim: SimCardInfo) {
        scope.launch {
            busy = true; busyLabel = "正在采集基线…"
            report = withContext(Dispatchers.IO) {
                DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
            }
            busy = false
        }
    }

    // getDataNetworkType() throws SecurityException without READ_PHONE_STATE. Asking for it up
    // front makes the network type readable *both* before and after the probe, so it becomes a
    // genuinely comparable field rather than a permission artifact. If it is denied, the
    // comparison degrades to "not comparable" instead of looking like a mutation.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { selected?.let { collect(it) } }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    LaunchedEffect(selected) { selected?.let { collect(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电话状态诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (simCards.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    simCards.forEach { sim ->
                        FilterChip(
                            selected = selected?.subId == sim.subId,
                            onClick = { selected = sim },
                            label = { Text("SIM ${sim.slot}") }
                        )
                    }
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busyLabel)
                }
            }

            val r = report
            if (r == null) {
                if (!busy) Text("未能采集到 SIM 信息。请确认已插卡并已授权 Shizuku。")
            } else {
                DeviceCard(r)

                ValueSource.entries.forEach { source ->
                    val values = r.bySource(source)
                    if (values.isNotEmpty()) SourceCard(source, values)
                }

                ProbeCard(
                    report = r,
                    enabled = !busy,
                    onRun = { confirmProbe = true }
                )

                CountryExperimentCard(
                    report = r,
                    result = experiment,
                    country = experimentCountry,
                    onCountryChange = { input ->
                        if (input.length <= 2 && input.all { it.isLetter() }) {
                            experimentCountry = input.lowercase()
                        }
                    },
                    enabled = !busy,
                    onRun = { confirmExperiment = true }
                )

                OutlinedButton(
                    onClick = {
                        val text = ReportFormatter.format(r) +
                                (experiment?.let { "\n" + ReportFormatter.formatCountryExperiment(it) } ?: "")
                        copyToClipboard(context, text)
                        Toast.makeText(context, "报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制完整报告") }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmProbe) {
        val r = report
        AlertDialog(
            onDismissRequest = { confirmProbe = false },
            title = { Text("运行 CarrierService 探测？") },
            text = {
                Text(
                    "这会短暂地：\n" +
                            "• 用 SIM 的真实 MCC/MNC 与 SPN 调用 setCarrierTestOverride（值不变）\n" +
                            "• 把本应用注册为 CarrierService\n" +
                            "• 只返回一个随机哨兵键，不含任何真实电话参数\n" +
                            "• 立即全部还原，并对比探测前后的身份值\n\n" +
                            "不会修改 MCC/MNC、国家码、APN 或 SIM 卡本身。\n" +
                            "若探测中出现任何异常，请立即在主界面点击「还原设置」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProbe = false
                    val sim = selected ?: return@TextButton
                    scope.launch {
                        busy = true; busyLabel = "正在探测（约 10-25 秒）…"
                        val probe = withContext(Dispatchers.IO) {
                            CarrierServiceProbe.run(context, sim.slot - 1, sim.subId)
                        }
                        report = withContext(Dispatchers.IO) {
                            DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                .copy(probe = probe)
                        }
                        busy = false
                    }
                }, enabled = r?.probeIsSafe == true) { Text("开始探测") }
            },
            dismissButton = {
                TextButton(onClick = { confirmProbe = false }) { Text("取消") }
            }
        )
    }

    if (confirmExperiment) {
        val r = report
        AlertDialog(
            onDismissRequest = { confirmExperiment = false },
            title = { Text("运行 SIM 国家码实验（$experimentCountry）？") },
            text = {
                Text(
                    "这会短暂地把 SIM 国家码覆盖为「$experimentCountry」，用来验证\n" +
                            "KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING 在本机是否真的生效。\n\n" +
                            "只下发这一个键。不会修改：\n" +
                            "• SIM / 网络 MCC/MNC（仍为 46000）\n" +
                            "• 网络国家码（仍为 cn）\n" +
                            "• Carrier ID、APN、漫游状态\n" +
                            "• 物理 SIM 卡\n\n" +
                            "结束后自动还原，并逐项对比实验前后的值。\n" +
                            "若出现异常，请到主界面点击「还原设置」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExperiment = false
                    val sim = selected ?: return@TextButton
                    scope.launch {
                        busy = true; busyLabel = "正在运行国家码实验（约 20-40 秒）…"
                        val res = withContext(Dispatchers.IO) {
                            CountryOverrideExperiment.run(
                                context, sim.slot - 1, sim.subId, experimentCountry
                            )
                        }
                        experiment = res
                        report = withContext(Dispatchers.IO) {
                            DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                .copy(probe = report?.probe)
                        }
                        busy = false
                    }
                }, enabled = r?.probeIsSafe == true) { Text("开始实验") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExperiment = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 「只改 SIM 国家码」实验。
 *
 * Reports A/B/C/D as four independent lines, because "the config was accepted" and "the country
 * actually changed" are genuinely different findings and conflating them would hide the exact
 * thing this experiment exists to measure.
 */
@Composable
private fun CountryExperimentCard(
    report: DiagnosticReport,
    result: CountryOverrideResult?,
    country: String,
    onCountryChange: (String) -> Unit,
    enabled: Boolean,
    onRun: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("SIM 国家码覆盖实验", style = MaterialTheme.typography.titleMedium)
            Text(
                "只下发 KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING 一个键，验证 getSimCountryIso() 是否真的改变。" +
                        "不改 MCC/MNC、不改网络、不改 APN，结束后自动还原。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = country,
                onValueChange = onCountryChange,
                label = { Text("测试国家码（2 位字母）") },
                singleLine = true,
                isError = country.length != 2,
                modifier = Modifier.fillMaxWidth()
            )

            result?.let { r ->
                Spacer(Modifier.height(8.dp))
                r.steps.forEach { s ->
                    Text(
                        "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { "\n     $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Verdict("A. CarrierConfig 接受该键", r.overrideAccepted)
                Verdict(
                    "B. getSimCountryIso() 改变 (${r.simCountryBefore ?: "?"} → ${r.simCountryDuring ?: "?"})",
                    r.simCountryChanged && r.simCountryMatchesRequest
                )
                Verdict("C. 网络国家码保持不变", r.networkCountryHeld)
                Verdict("D. SIM MCC/MNC 保持不变", r.simOperatorHeld)
                Verdict("   Carrier ID 保持不变", r.carrierIdHeld)
                Verdict("   APN 保持不变", r.apnHeld)
                Spacer(Modifier.height(6.dp))
                Text(
                    "结论：${r.verdict.label}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (r.verdict == OverrideVerdict.EFFECTIVE)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Verdict("已自动还原（SIM 国家码现为 ${r.simCountryAfter ?: "?"}）", r.revertRestored)
                if (r.unexpectedSideEffects.isNotEmpty()) {
                    Text(
                        "⚠️ 意外副作用：${r.unexpectedSideEffects.joinToString(", ") { it.key }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = onRun,
                enabled = enabled && report.probeIsSafe && country.length == 2,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (result == null) "运行国家码实验" else "重新运行") }
        }
    }
}

@Composable
private fun Verdict(label: String, ok: Boolean) {
    Text(
        "${if (ok) "✅" else "❌"} $label",
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun DeviceCard(r: DiagnosticReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("设备", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(r.device, style = MaterialTheme.typography.bodySmall)
            Text(
                "Android ${r.androidRelease} (API ${r.sdkInt}) · 安全补丁 ${r.securityPatch}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("卡槽 ${r.slot} · subId ${r.subId}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                r.existingCarrierServicePackage
                    ?.let { "⚠️ 已有 CarrierService 绑定: $it" }
                    ?: "当前无 CarrierService 绑定（可安全探测）",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.probeIsSafe) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SourceCard(source: ValueSource, values: List<DiagnosticValue>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(source.label, style = MaterialTheme.typography.titleMedium)
            Text(
                source.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            values.forEach { v ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(v.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${v.mutability.label} · ${v.visibility.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        v.display,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (v.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeCard(report: DiagnosticReport, enabled: Boolean, onRun: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("CarrierService 机制探测", style = MaterialTheme.typography.titleMedium)
            Text(
                "验证 Android 16 方案在本机是否真的可用。只返回随机哨兵键，不改任何真实电话参数，结束后自动还原。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            report.probe?.let { p ->
                p.steps.forEach { s ->
                    Text(
                        "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { "\n     $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Two independent verdicts. A field merely becoming readable must never drag the
                // mechanism verdict down.
                Text(
                    if (p.mechanismWorks) "机制结论：Android 16 CarrierService 方案在本机可用 ✅"
                    else "机制结论：不可用 ❌",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (p.mechanismWorks) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    if (p.revertClean) "还原结论：身份值已完好还原 ✅"
                    else "还原结论：存在未还原的身份值 ❌",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (p.revertClean) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )

                if (p.mutations.isNotEmpty()) {
                    Text(
                        "⚠️ 以下身份值发生变化：${p.mutations.joinToString(", ") { it.key }}\n" +
                                "请到主界面点击「还原设置」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (p.notes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "观测差异（不影响结论）",
                        style = MaterialTheme.typography.labelMedium
                    )
                    p.notes.forEach {
                        Text(
                            ReportFormatter.describe(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!report.probeIsSafe) {
                Text(
                    "已禁用：本机已有其它应用被绑定为 CarrierService，顶替它可能丢失其提供的配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = onRun,
                enabled = enabled && report.probeIsSafe,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (report.probe == null) "运行探测" else "重新探测") }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Nrfr diagnostics", text))
}
