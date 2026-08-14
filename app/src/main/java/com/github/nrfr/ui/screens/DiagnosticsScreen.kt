package com.github.nrfr.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.github.nrfr.diag.*
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.model.SimCardInfo
import com.github.nrfr.region.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 地区 Profile / 诊断界面。
 *
 * Structure follows the architecture rather than the history of experiments: a read-only snapshot,
 * a capability matrix, one transactional profile runner, and two recovery actions. Earlier
 * iterations accumulated a card per one-off experiment; that is deliberately gone.
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
    var confirmProfile by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf(RegionalProfile.PRESETS.first()) }
    var txResult by remember { mutableStateOf<TransactionResult?>(null) }
    var recoveryCountry by remember { mutableStateOf("cn") }
    var recoverySteps by remember { mutableStateOf<List<ProbeStep>>(emptyList()) }
    var releaseSteps by remember { mutableStateOf<List<ProbeStep>>(emptyList()) }
    var pendingRecovery by remember { mutableStateOf(false) }

    fun collect(sim: SimCardInfo) {
        scope.launch {
            busy = true; busyLabel = "正在采集快照…"
            report = withContext(Dispatchers.IO) {
                DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
            }
            pendingRecovery = withContext(Dispatchers.IO) { RecoveryManager.hasPendingWork(context) }
            busy = false
        }
    }

    // getDataNetworkType() throws SecurityException without READ_PHONE_STATE. Asking up front makes
    // the network type comparable before and after a transaction rather than a permission artifact.
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
                title = { Text("地区 Profile / 诊断") },
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

            if (pendingRecovery) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("检测到未完成的事务", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "上一次事务没有干净收尾（可能因崩溃、进程被杀或重启）。点击补完回滚。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true; busyLabel = "正在补完回滚…"
                                    val outcomes = withContext(Dispatchers.IO) {
                                        RecoveryManager.recoverAll(context)
                                    }
                                    recoverySteps = outcomes.flatMap { it.steps }
                                    selected?.let { sim ->
                                        report = withContext(Dispatchers.IO) {
                                            DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                        }
                                    }
                                    pendingRecovery = withContext(Dispatchers.IO) {
                                        RecoveryManager.hasPendingWork(context)
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("补完回滚") }
                    }
                }
            }

            val r = report
            if (r == null) {
                if (!busy) Text("未能采集到 SIM 信息。请确认已插卡并已授权 Shizuku。")
            } else {
                DeviceCard(r)
                CapabilityCard()

                ProfileCard(
                    profiles = RegionalProfile.PRESETS,
                    selected = selectedProfile,
                    onSelect = { selectedProfile = it },
                    result = txResult,
                    enabled = !busy && r.probeIsSafe,
                    blockedReason = if (!r.probeIsSafe)
                        "已有其它应用被绑定为 CarrierService（${r.existingCarrierServicePackage}）"
                    else null,
                    onRun = { confirmProfile = true }
                )

                ValueSource.entries.forEach { source ->
                    val values = r.bySource(source)
                    if (values.isNotEmpty()) SourceCard(source, values)
                }

                ProbeCard(report = r, enabled = !busy, onRun = { confirmProbe = true })

                ReleaseCard(
                    boundPackage = r.existingCarrierServicePackage,
                    ourPackage = context.packageName,
                    steps = releaseSteps,
                    enabled = !busy,
                    onRun = {
                        val sim = selected ?: return@ReleaseCard
                        scope.launch {
                            busy = true; busyLabel = "正在释放 CarrierService…"
                            releaseSteps = withContext(Dispatchers.IO) {
                                CarrierServiceRelease.release(context, sim.slot - 1, sim.subId).steps
                            }
                            report = withContext(Dispatchers.IO) {
                                DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                    .copy(probe = report?.probe)
                            }
                            busy = false
                        }
                    }
                )

                RecoveryCard(
                    target = recoveryCountry,
                    onTargetChange = { input ->
                        if (input.length <= 2 && input.all { it.isLetter() }) {
                            recoveryCountry = input.lowercase()
                        }
                    },
                    steps = recoverySteps,
                    enabled = !busy && recoveryCountry.length == 2,
                    onRun = {
                        val sim = selected ?: return@RecoveryCard
                        scope.launch {
                            busy = true; busyLabel = "正在恢复 SIM 国家码…"
                            recoverySteps = withContext(Dispatchers.IO) {
                                CountryIsoRestore.forceRestore(
                                    context, sim.slot - 1, sim.subId, recoveryCountry
                                )
                            }
                            report = withContext(Dispatchers.IO) {
                                DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                    .copy(probe = report?.probe)
                            }
                            busy = false
                        }
                    }
                )

                OutlinedButton(
                    onClick = {
                        val text = ReportFormatter.format(r) +
                                "\n" + ReportFormatter.formatCapabilities() +
                                (txResult?.let { "\n" + ReportFormatter.formatTransaction(it) } ?: "")
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
                    "只返回一个随机哨兵键，不含任何真实电话参数，结束后自动还原并释放绑定。\n" +
                            "用于验证机制本身，不改变任何身份值。"
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
            dismissButton = { TextButton(onClick = { confirmProbe = false }) { Text("取消") } }
        )
    }

    if (confirmProfile) {
        val r = report
        AlertDialog(
            onDismissRequest = { confirmProfile = false },
            title = { Text("应用 Profile：${selectedProfile.name}？") },
            text = {
                Text(
                    buildString {
                        appendLine("将改动以下信号：")
                        selectedProfile.touchedSignals().forEach {
                            appendLine("• ${it.label} — ${SignalCapabilities[it].status.label}")
                        }
                        appendLine()
                        if (selectedProfile.usesExperimentalMechanism()) {
                            appendLine("⚠️ 含尚未在真机验证的机制（MCC/MNC）。")
                            appendLine("它会连带改变 Carrier ID，并可能影响 APN 匹配与移动数据。")
                            appendLine()
                        }
                        appendLine("不会改动：网络国家码 / 网络 MCC/MNC / APN / 物理 SIM。")
                        appendLine("事务结束时自动还原，并逐项验证清理是否完整。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProfile = false
                    val sim = selected ?: return@TextButton
                    scope.launch {
                        busy = true; busyLabel = "正在执行事务（约 30-60 秒）…"
                        txResult = withContext(Dispatchers.IO) {
                            RegionTransaction.run(context, sim.slot - 1, sim.subId, selectedProfile)
                        }
                        report = withContext(Dispatchers.IO) {
                            DiagnosticCollector.collect(context, sim.slot - 1, sim.subId)
                                .copy(probe = report?.probe)
                        }
                        pendingRecovery = withContext(Dispatchers.IO) {
                            RecoveryManager.hasPendingWork(context)
                        }
                        busy = false
                    }
                }, enabled = r?.probeIsSafe == true) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { confirmProfile = false }) { Text("取消") } }
        )
    }
}

// ------------------------------------------------------------------------ cards

@Composable
private fun CapabilityCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("信号能力矩阵", style = MaterialTheme.typography.titleMedium)
            Text(
                "真机验证基准：${SignalCapabilities.VERIFIED_ON}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SignalCapabilities.all().forEach { c ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.signal.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${c.signal.provenance.label} · ${c.signal.mechanism.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(c.status.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 地区 Profile 卡片 —— 取代此前一个个堆叠的一次性实验卡。 */
@Composable
private fun ProfileCard(
    profiles: List<RegionalProfile>,
    selected: RegionalProfile,
    onSelect: (RegionalProfile) -> Unit,
    result: TransactionResult?,
    enabled: Boolean,
    blockedReason: String?,
    onRun: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("地区 Profile（事务型）", style = MaterialTheme.typography.titleMedium)
            Text(
                "快照 → 校验基线 → 应用 → 验证 → 还原 → 释放 → 验证清理完整。" +
                        "任何一步失败都会自动回滚；崩溃或重启也会在下次启动时补完。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            profiles.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == p, onClick = { onSelect(p) })
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            p.touchedSignals().joinToString(", ") { it.label },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            result?.let { res ->
                Spacer(Modifier.height(8.dp))
                res.steps.forEach { s ->
                    Text(
                        "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { "\n     $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                res.effects.forEach { e ->
                    Text(
                        "${e.signal.label}: ${e.before ?: "?"} → ${e.during ?: "?"}  ${e.outcome.label}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("清理完整性", style = MaterialTheme.typography.labelMedium)
                Verdict("原始身份已还原", res.cleanup.identityRestored)
                Verdict("CarrierConfig 已还原", res.cleanup.carrierConfigRestored)
                Verdict("CarrierService 已释放", res.cleanup.carrierServiceReleased)
                Verdict("carrier privileges 已撤销", res.cleanup.carrierPrivilegesReleased)
                Verdict("无意外身份变化", res.cleanup.noUnexpectedChanges)
                Verdict("APN/数据未受损", res.cleanup.apnDataIntact)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (res.success) "事务结论：成功 ✅" else "事务结论：未成功 ❌",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (res.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!res.cleanup.complete) {
                    Text(
                        "未满足：${res.cleanup.failures().joinToString("; ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            blockedReason?.let {
                Text(
                    "已禁用：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (result == null) "执行事务" else "重新执行")
            }
        }
    }
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
                    ?: "当前无 CarrierService 绑定",
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
                "只返回随机哨兵键，不改任何真实电话参数，结束后自动还原并释放绑定。",
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
                Verdict("机制可用（onLoadConfig 被回调且配置已合并）", p.mechanismWorks)
                Verdict("身份值已完好还原", p.revertClean)
                if (p.notes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("观测差异（不影响结论）", style = MaterialTheme.typography.labelMedium)
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
                    "已禁用：本机已有其它应用被绑定为 CarrierService。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedButton(
                onClick = onRun,
                enabled = enabled && report.probeIsSafe,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (report.probe == null) "运行探测" else "重新探测") }
        }
    }
}

/**
 * 释放卡片：把本应用从 CarrierService 绑定中解除。
 *
 * Needed as a standalone action because a stranded binding blocks every subsequent transaction:
 * the safety precondition sees a CarrierService already bound and refuses to run.
 */
@Composable
private fun ReleaseCard(
    boundPackage: String?,
    ourPackage: String,
    steps: List<ProbeStep>,
    enabled: Boolean,
    onRun: () -> Unit
) {
    val stranded = boundPackage == ourPackage
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("释放 CarrierService", style = MaterialTheme.typography.titleMedium)
            Text(
                if (stranded)
                    "⚠️ 本应用当前仍被绑定为 CarrierService，会导致后续事务被安全检查拒绝。"
                else
                    "当前绑定：${boundPackage ?: "(无)"}。用于清理残留绑定，可安全重复执行。",
                style = MaterialTheme.typography.bodySmall,
                color = if (stranded) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "顺序：先撤销 carrier privileges，再清除 override，然后轮询确认框架真的已不再绑定。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            steps.forEach { s ->
                Text(
                    "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("释放 CarrierService")
            }
        }
    }
}

/** 恢复卡片：把 SIM 国家码强制写回指定值。 */
@Composable
private fun RecoveryCard(
    target: String,
    onTargetChange: (String) -> Unit,
    steps: List<ProbeStep>,
    enabled: Boolean,
    onRun: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("恢复 SIM 国家码", style = MaterialTheme.typography.titleMedium)
            Text(
                "若 SIM 国家码被留在了错误的值上，用这里写回去。\n" +
                        "提示：切换飞行模式约 10 秒或重启，同样能让系统从 IMSI 重新读取真实国家码，" +
                        "且完全不需要本应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = target,
                onValueChange = onTargetChange,
                label = { Text("目标国家码（原值，通常为 cn）") },
                singleLine = true,
                isError = target.length != 2,
                modifier = Modifier.fillMaxWidth()
            )
            steps.forEach { s ->
                Text(
                    "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("恢复为 $target")
            }
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

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Nrfr diagnostics", text))
}
