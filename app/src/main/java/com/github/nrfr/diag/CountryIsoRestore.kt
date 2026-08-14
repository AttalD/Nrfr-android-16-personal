package com.github.nrfr.diag

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.PrivilegedTelephony

/** 还原动作的决策结果（纯逻辑，可单测）。 */
enum class RestoreAction {
    /** 当前值已经等于基线，无需动作。 */
    NOTHING_TO_DO,

    /** 需要把基线值主动推回去。 */
    PUSH_BASELINE,

    /** 没有可用的基线（探测前就读不到），无法安全还原。 */
    CANNOT_RESTORE
}

enum class RestoreOutcome(val label: String) {
    ALREADY_CORRECT("无需还原"),
    RESTORED("已还原"),
    FAILED("还原失败"),
    NO_BASELINE("无基线可还原")
}

/**
 * SIM 国家码的还原。
 *
 * ## 为什么不能靠「删掉这个键」来还原
 *
 * `UiccProfile.handleSimCountryIsoOverride()` 的判断是：
 *
 * ```java
 * String iso = config.getString(KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING);
 * if (!TextUtils.isEmpty(iso) && !iso.equals(getSimCountryIsoForPhone(mPhoneId))) {
 *     mTelephonyManager.setSimCountryIsoForPhone(mPhoneId, iso);
 *     SubscriptionManagerService.getInstance().setCountryIso(subId, iso);
 * }
 * ```
 *
 * 键被移除后 `iso` 为空 → **整个分支被跳过** → `gsm.sim.operator.iso-country`
 * 保持上一次写入的值。框架里没有"取消覆盖"这条路径：唯一会把它写回真实值的是
 * `SIMRecords.onAllRecordsLoaded()`（SIM 重新加载 / 重启 / 飞行模式切换）
 * 与 `UiccProfile.resetProperties()`。
 *
 * 所以还原必须**主动把基线值再推一次**，等它生效，然后才移除这个键。
 */
object CountryIsoRestore {

    private const val TAG = "Nrfr/Restore"
    private const val WAIT_STEP_MS = 250L
    private const val WAIT_TOTAL_MS = 15_000L

    /** 纯决策逻辑。 */
    fun decide(baseline: String?, current: String?): RestoreAction = when {
        baseline.isNullOrBlank() -> RestoreAction.CANNOT_RESTORE
        current != null && current.equals(baseline, ignoreCase = true) -> RestoreAction.NOTHING_TO_DO
        else -> RestoreAction.PUSH_BASELINE
    }

    /**
     * 把 [baseline] 推回去并等待 `getSimCountryIso()` 真正变回该值。
     *
     * **必须在仍然注册为 CarrierService 的状态下调用** —— 一旦解除注册，框架就不会再向我们
     * 索取配置，也就无从推回。
     */
    fun restore(context: Context, subId: Int, baseline: String?): RestoreOutcome {
        val current = readSimCountry(context, subId)
        return when (decide(baseline, current)) {
            RestoreAction.CANNOT_RESTORE -> {
                Log.w(TAG, "no baseline to restore to (current=$current)")
                RestoreOutcome.NO_BASELINE
            }

            RestoreAction.NOTHING_TO_DO -> RestoreOutcome.ALREADY_CORRECT

            RestoreAction.PUSH_BASELINE -> {
                val target = baseline!!.lowercase()
                Log.i(TAG, "pushing baseline country '$target' back (current=$current)")

                // 1. Serve the baseline value and let the framework write it to the property.
                CarrierServiceBridge.restoreCountryIso = target
                runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                val landed = waitFor {
                    readSimCountry(context, subId)?.equals(target, ignoreCase = true) == true
                }

                // 2. Drop our key again so the merged config returns to its original shape.
                CarrierServiceBridge.restoreCountryIso = null
                runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                waitFor(5_000L) { readConfigCountry(context, subId) == null }

                if (landed) RestoreOutcome.RESTORED else RestoreOutcome.FAILED
            }
        }
    }

    /**
     * 一次性恢复动作：为一台**已经被卡在错误国家码**上的设备，主动把 [target] 推回去。
     *
     * Needed because the property is one-way: if a previous run left it overridden and the app no
     * longer holds a baseline, there is nothing to "revert" — the value has to be written again
     * deliberately. Registers, pushes, drops the key, and unregisters, all in one shot.
     *
     * (Toggling airplane mode or rebooting achieves the same thing via
     * `SIMRecords.onAllRecordsLoaded()`, and needs no privileges at all.)
     */
    fun forceRestore(context: Context, slot: Int, subId: Int, target: String): List<ProbeStep> {
        val steps = mutableListOf<ProbeStep>()
        val iso = target.trim().lowercase()
        val current = readSimCountry(context, subId)
        steps += ProbeStep("当前 SIM 国家码", true, current ?: "(不可读)")

        if (current?.equals(iso, ignoreCase = true) == true) {
            steps += ProbeStep("无需恢复", true, "已经是 $iso")
            return steps
        }

        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }.getOrNull()
        if (!existing.isNullOrBlank() && existing != context.packageName) {
            steps += ProbeStep("安全前置检查", false, "已有其它 CarrierService ($existing)，已中止")
            return steps
        }

        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            steps += ProbeStep("读取签名哈希", false, "失败")
            return steps
        }

        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        var registered = false
        var outcome = RestoreOutcome.FAILED

        try {
            PrivilegedTelephony.applyCarrierPrivileges(subId, certs.first(), realMccMnc, realSpn)
            PrivilegedTelephony.setCarrierServicePackageOverride(
                subId, context.packageName, context.packageName
            )
            registered = true
            steps += ProbeStep("注册为 CarrierService", true)
            outcome = restore(context, subId, iso)
        } catch (t: Throwable) {
            Log.e(TAG, "forceRestore failed", t)
            steps += ProbeStep("恢复过程异常", false, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            if (registered) {
                val rel = runCatching {
                    CarrierServiceRelease.release(context, slot, subId, realMccMnc, realSpn)
                }.getOrNull()
                steps += ProbeStep(
                    "释放 CarrierService", rel?.released == true,
                    "框架报告绑定 = ${rel?.boundPackageAfter ?: "(无)"}"
                )
                runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
            }
            CarrierServiceBridge.reset()
        }

        val after = readSimCountry(context, subId)
        steps += ProbeStep(
            "恢复结果", after?.equals(iso, ignoreCase = true) == true,
            "${outcome.label} · 现在为 ${after ?: "(不可读)"}"
        )
        return steps
    }

    fun readSimCountry(context: Context, subId: Int): String? = runCatching {
        val tm = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .let { runCatching { it.createForSubscriptionId(subId) }.getOrDefault(it) }
        tm.simCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun readConfigCountry(context: Context, subId: Int): String? = runCatching {
        PrivilegedTelephony.currentConfig(subId, context.packageName)
            ?.getString(CarrierConfigKeys.KEY_SIM_COUNTRY_ISO)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun waitFor(totalMs: Long = WAIT_TOTAL_MS, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (condition()) return true
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return condition()
    }
}
