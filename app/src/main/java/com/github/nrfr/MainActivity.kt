package com.github.nrfr

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.nrfr.data.OverrideStore
import com.github.nrfr.manager.ApplyResult
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.region.RecoveryManager
import com.github.nrfr.ui.screens.AboutScreen
import com.github.nrfr.ui.screens.DiagnosticsScreen
import com.github.nrfr.ui.screens.MainScreen
import com.github.nrfr.ui.screens.ShizukuNotReadyScreen
import com.github.nrfr.ui.theme.NrfrTheme
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var isShizukuReady by mutableStateOf(false)
    private var showAbout by mutableStateOf(false)
    private var showDiagnostics by mutableStateOf(false)
    private var reapplyDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化 Hidden API 访问
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        // 检查 Shizuku 状态
        checkShizukuStatus()

        // 添加 Shizuku 权限监听器
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            isShizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
            if (!isShizukuReady) {
                Toast.makeText(this, "需要 Shizuku 权限才能运行", Toast.LENGTH_LONG).show()
            }
        }

        // 添加 Shizuku 绑定监听器
        Shizuku.addBinderReceivedListener {
            checkShizukuStatus()
        }

        setContent {
            NrfrTheme {
                when {
                    showAbout -> AboutScreen(onBack = { showAbout = false })
                    showDiagnostics && isShizukuReady ->
                        DiagnosticsScreen(onBack = { showDiagnostics = false })

                    isShizukuReady -> MainScreen(
                        onShowAbout = { showAbout = true },
                        onShowDiagnostics = { showDiagnostics = true }
                    )

                    else -> ShizukuNotReadyScreen()
                }
            }
        }
    }

    private fun checkShizukuStatus() {
        isShizukuReady = if (Shizuku.getBinder() == null) {
            Toast.makeText(this, "请先安装并启用 Shizuku", Toast.LENGTH_LONG).show()
            false
        } else {
            val hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Shizuku.requestPermission(0)
            }
            hasPermission
        }
        if (isShizukuReady) reapplyAfterReboot()
    }

    /**
     * 开机后重新应用已保存的配置。
     *
     * `setCarrierTestOverride` / `setCarrierServicePackageOverride` are in-memory framework state,
     * so a reboot drops them. `BootReceiver` retries first, but on a non-rooted device Shizuku is
     * not running that early — the reliable moment is the first time the user opens Nrfr with
     * Shizuku granted, which is here.
     */
    private fun reapplyAfterReboot() {
        if (reapplyDone) return
        reapplyDone = true

        // Finish any transaction that did not close cleanly (crash, process death, reboot).
        // Runs before re-applying anything so we never stack a new override on a dirty state.
        if (RecoveryManager.hasPendingWork(this)) {
            val outcomes = runCatching { RecoveryManager.recoverAll(this) }.getOrNull().orEmpty()
            val stillDirty = outcomes.count { !it.recovered }
            Toast.makeText(
                this,
                if (stillDirty == 0) "已自动补完上次未完成的回滚"
                else "有 $stillDirty 个事务仍未回滚干净，请打开诊断界面处理",
                Toast.LENGTH_LONG
            ).show()
        }

        if (OverrideStore.configuredSubIds(this).isEmpty()) return
        val results = runCatching { CarrierConfigManager.reapplyAll(this) }.getOrNull().orEmpty()
        val failed = results.values.filterIsInstance<ApplyResult.Failure>()
        if (results.isNotEmpty() && failed.isEmpty()) {
            Toast.makeText(this, "已重新应用保存的配置", Toast.LENGTH_SHORT).show()
        } else if (failed.isNotEmpty()) {
            Toast.makeText(this, "重新应用失败: ${failed.first().message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener { _, _ -> }
        Shizuku.removeBinderReceivedListener { }
    }
}
