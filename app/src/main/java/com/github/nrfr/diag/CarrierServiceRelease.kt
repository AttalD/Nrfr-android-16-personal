package com.github.nrfr.diag

import android.content.Context
import android.util.Log
import com.github.nrfr.manager.PrivilegedTelephony

data class ReleaseResult(
    val steps: List<ProbeStep>,
    /** 释放后框架报告的绑定包名；null 表示无绑定。 */
    val boundPackageAfter: String?,
    val released: Boolean
)

/**
 * 显式、幂等、**可验证**的 CarrierService 释放。
 *
 * ## 为什么单纯调用 `setCarrierServicePackageOverride(subId, null, …)` 不够
 *
 * 两个问题：
 *
 * **顺序**。`CarrierPrivilegesTracker.getCarrierService()` 的选取逻辑是：
 *
 * ```java
 * if (mTestOverrideCarrierServicePackage != null
 *         && !mTestOverrideCarrierServicePackage.equals(packageName)) continue;
 * if (simPrivilegedPackages.contains(packageName)) { carrierServicePackageName = packageName; break; }
 * ```
 *
 * 先清除 `mTestOverrideCarrierServicePackage` 时，那道 `continue` 过滤器就消失了，而此刻我们
 * **仍然持有 carrier privileges**（`mTestOverrideRules` 还没清），并且本应用在 manifest 里确实
 * 声明了 `CarrierService`。于是这一次重算反而会把我们选成"普通的"运营商服务。
 * 因此必须**先撤销 privileges，再清除 override**，让最后一次重算发生在我们已不具备资格之后。
 *
 * **异步**。两个调用都是 `mCurrentHandler.sendMessage(...)`，重算在 CPT 的 handler 线程上进行；
 * 调用返回后立刻读 `getCarrierServicePackageNameForLogicalSlot()` 会读到**尚未更新的缓存**
 * （`mPrivilegedPackageInfo.mCarrierService.first`）。所以必须轮询确认，而不是发完就当成功。
 */
object CarrierServiceRelease {

    private const val TAG = "Nrfr/Release"
    private const val WAIT_STEP_MS = 250L
    private const val VERIFY_TIMEOUT_MS = 8_000L
    private const val MAX_ATTEMPTS = 3

    /** 纯判定：框架报告的绑定包名是否意味着我们已经释放。 */
    fun isReleased(boundPackage: String?, ourPackage: String): Boolean =
        boundPackage.isNullOrBlank() || boundPackage != ourPackage

    /**
     * 撤销 privileges → 清除 CarrierService override → 轮询验证。最多重试 [MAX_ATTEMPTS] 次。
     *
     * [realMccMnc] / [realSpn] 会原样回填给 `setCarrierTestOverride`，避免把
     * `gsm.sim.operator.numeric` / `.alpha` 清空。传 null 时会现场读取。
     */
    fun release(
        context: Context,
        slot: Int,
        subId: Int,
        realMccMnc: String? = null,
        realSpn: String? = null
    ): ReleaseResult {
        val steps = mutableListOf<ProbeStep>()
        val ours = context.packageName
        val mccMnc = realMccMnc ?: PrivilegedTelephony.realMccMnc(context, subId)
        val spn = realSpn ?: PrivilegedTelephony.realSpn(context, subId)

        val boundBefore = readBound(slot)
        steps += ProbeStep("释放前绑定状态", true, boundBefore ?: "(无绑定)")

        if (isReleased(boundBefore, ours)) {
            steps += ProbeStep("无需释放", true, "本应用当前未被绑定为 CarrierService")
            return ReleaseResult(steps, boundBefore, released = true)
        }

        var bound: String? = boundBefore
        for (attempt in 1..MAX_ATTEMPTS) {
            // 1. Drop carrier privileges FIRST, so the final recompute cannot re-select us.
            val cleared = runCatching {
                PrivilegedTelephony.clearCarrierPrivileges(subId, mccMnc, spn)
            }
            steps += ProbeStep(
                "第 $attempt 次 · 撤销 carrier privileges", cleared.isSuccess, cleared.err()
            )

            // 2. Then remove the CarrierService override.
            val unregistered = runCatching {
                PrivilegedTelephony.setCarrierServicePackageOverride(subId, null, ours)
            }
            steps += ProbeStep(
                "第 $attempt 次 · 清除 CarrierService override",
                unregistered.isSuccess, unregistered.err()
            )

            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }

            // 3. The recompute happens on CPT's handler thread — poll, do not assume.
            val ok = waitFor {
                bound = readBound(slot)
                isReleased(bound, ours)
            }
            if (ok) {
                steps += ProbeStep(
                    "验证已释放", true,
                    "框架报告绑定 = ${bound ?: "(无)"}（第 $attempt 次尝试后）"
                )
                return ReleaseResult(steps, bound, released = true)
            }
            Log.w(TAG, "still bound after attempt $attempt: $bound")
        }

        steps += ProbeStep(
            "验证已释放", false,
            "重试 $MAX_ATTEMPTS 次后框架仍报告绑定 = ${bound ?: "(无)"}；" +
                    "可切换飞行模式或重启，绑定只存在于框架内存中"
        )
        return ReleaseResult(steps, bound, released = false)
    }

    fun readBound(slot: Int): String? =
        runCatching { PrivilegedTelephony.carrierServicePackage(slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun waitFor(condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            if (condition()) return true
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return condition()
    }

    private fun Result<*>.err(): String? = exceptionOrNull()?.let { t ->
        val root = generateSequence(t) { it.cause }.last()
        "${root.javaClass.simpleName}: ${root.message}"
    }
}
