package com.github.nrfr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.nrfr.diag.ProbeStep
import com.github.nrfr.region.ProfileState
import com.github.nrfr.region.RegionalProfile

/**
 * 持久化 Region Profile 的主控件。
 *
 * The one rule this UI must never break: it shows **SIM/框架侧** and **实际网络侧** as two clearly
 * separated groups. Nothing here may imply the cellular network itself changed — it did not, and
 * cannot without root.
 */
@Composable
fun RegionProfileCard(
    profile: RegionalProfile,
    state: ProfileState,
    snapshot: Map<String, String?>,
    steps: List<ProbeStep>,
    busy: Boolean,
    onApply: () -> Unit,
    onRestore: () -> Unit
) {
    val active = state == ProfileState.ACTIVE
    val needsRecovery = state == ProfileState.RECOVERY_REQUIRED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                needsRecovery -> MaterialTheme.colorScheme.errorContainer
                active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${profile.name} — ${state.label}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    active -> "SIM/框架侧身份已被覆盖。实际蜂窝网络未改变。"
                    needsRecovery -> "状态不明确，请执行恢复。"
                    else -> "当前使用 SIM 的真实身份。"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "SIM / 框架侧（本应用可覆盖）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row2("国家码", snapshot["sim_country"])
            Row2("运营商名", snapshot["sim_operator_name"])
            Row2("MCC/MNC", snapshot["sim_operator"])
            Row2("Carrier ID", snapshot["sim_carrier_id"])
            Row2("CarrierConfig 国家码", snapshot["carrier_config_country"] ?: "(未设置)")

            Spacer(Modifier.height(10.dp))
            Text(
                "实际蜂窝网络（只读 · 永不修改）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row2("网络 MCC/MNC", snapshot["network_operator"])
            Row2("网络国家码", snapshot["network_country"])

            Spacer(Modifier.height(10.dp))
            Row2("移动数据", snapshot["data_health"])

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                steps.takeLast(14).forEach { s ->
                    Text(
                        "${if (s.ok) "✅" else "❌"} ${s.name}${s.detail?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.label)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = state == ProfileState.INACTIVE,
                        modifier = Modifier.weight(1f)
                    ) { Text("启用${profile.name}") }
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = active || needsRecovery || state == ProfileState.FAILED,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (needsRecovery) "执行恢复" else "恢复原始 SIM") }
                }
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value ?: "?",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 首次启用前的安全说明。 */
@Composable
fun FirstApplyWarningDialog(
    profile: RegionalProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用 ${profile.name}？") },
        text = {
            Text(
                "将要发生的事：\n" +
                        "• 覆盖 SIM/框架侧身份：国家码 → ${profile.countryIso ?: "不变"}、" +
                        "运营商名 → ${profile.operatorName ?: "不变"}、" +
                        "MCC/MNC → ${profile.operatorNumeric ?: "不变"}\n" +
                        "• **实际蜂窝网络不会改变** —— 你仍然连着原来的基站，" +
                        "网络侧 MCC/MNC 与国家码保持真实值\n" +
                        "• 物理 SIM 卡不会被写入或修改\n\n" +
                        "已知影响：\n" +
                        "• Carrier ID 会随 MCC/MNC 改变（由系统推导，属预期）\n" +
                        "• APN 选择依赖运营商代码，可能被重新匹配\n\n" +
                        "安全保障：\n" +
                        "• 启用前会记录并保存原始值，启用期间不会覆写\n" +
                        "• 若移动数据在稳定后仍不可用，会自动还原\n" +
                        "• 随时可点「恢复原始 SIM」还原，且会回读校验\n" +
                        "• 所有覆盖仅存在于系统内存，重启即失效"
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("启用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
