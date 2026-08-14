package com.github.nrfr.region

import android.content.Context
import com.github.nrfr.diag.ComparisonOutcome
import com.github.nrfr.diag.DiagnosticCollector
import com.github.nrfr.diag.ValueComparison

/** 数据连通性的健康度。 */
enum class DataHealth(val label: String) {
    HEALTHY("正常"),
    DEGRADED("异常"),
    UNKNOWN("未知")
}

/**
 * APN 与移动数据的安全判定。
 *
 * ## 为什么不能直接比较前后快照
 *
 * 早期实现把 `apn`、`data_state`、`data_validated` 三个键一起做快照比较，任何差异都算"受损"。
 * 这产生了两类**假阳性**：
 *
 * 1. **APN 读取权限的差异**。读 APN 需要 carrier privileges；事务期间我们有，事务结束后没有。
 *    于是 APN 从"可读"变成"不可读"，看起来像是被改了 —— 其实只是观测能力的变化。
 * 2. **数据状态的瞬时抖动**。电话配置重载期间 `data_state` 合法地经历
 *    `CONNECTED → DISCONNECTED → CONNECTED`，蜂窝 `Network` 对象也会被拆掉重建，
 *    `NET_CAPABILITY_INTERNET` 会短暂翻转。把某一瞬间的采样当成永久损坏是错的。
 *
 * 正确做法：
 * - **APN**：只有"两次都读得到、且值确实不同"才算被改动；
 * - **数据**：不比较瞬时快照，而是给一个恢复窗口，等它稳定下来再判定；
 *   并且只有在**基线本来是健康的**情况下，最终仍不健康才算是本次事务造成的损坏。
 *
 * 判定依然保守 —— 真正的连通性失败仍会被检出 —— 但技术上是正确的。
 */
object ApnDataSafety {

    private const val SETTLE_STEP_MS = 500L
    private const val SETTLE_TIMEOUT_MS = 20_000L

    /** APN 是否被真正改动（纯逻辑）。 */
    fun apnChanged(comparisons: List<ValueComparison>): Boolean =
        comparisons.any { it.key == "apn" && it.outcome == ComparisonOutcome.CHANGED }

    /**
     * 从采集到的两个字段推断健康度（纯逻辑）。
     *
     * `data_validated` 的形态是 `"internet=<bool> validated=<bool>"`。只要其中之一为真，
     * 就说明蜂窝数据是通的 —— `validated` 代表系统探测到了可用的互联网连接。
     */
    fun healthFrom(dataState: String?, dataValidated: String?): DataHealth {
        if (dataState == null && dataValidated == null) return DataHealth.UNKNOWN
        val validated = dataValidated?.contains("validated=true") == true
        val internet = dataValidated?.contains("internet=true") == true
        val connected = dataState == "CONNECTED"
        return when {
            validated || internet || connected -> DataHealth.HEALTHY
            dataValidated == null && dataState == null -> DataHealth.UNKNOWN
            dataValidated?.contains("无蜂窝网络") == true -> DataHealth.DEGRADED
            else -> DataHealth.DEGRADED
        }
    }

    /**
     * 本次事务是否造成了 APN/数据损坏（纯逻辑）。
     *
     * 只有两种情况算损坏：APN 值确实被改了，或者**基线健康、最终不健康**。
     * 基线本来就不健康（或未知）时，不能把责任算在事务头上。
     */
    fun isDamaged(apnChanged: Boolean, baseline: DataHealth, final: DataHealth): Boolean =
        apnChanged || (baseline == DataHealth.HEALTHY && final == DataHealth.DEGRADED)

    fun healthOf(values: List<com.github.nrfr.diag.DiagnosticValue>): DataHealth {
        fun read(key: String) = values.firstOrNull { it.key == key }
            ?.takeIf { it.error == null }?.value
        return healthFrom(read("data_state"), read("data_validated"))
    }

    /**
     * 等待数据连通性稳定下来，最多 [SETTLE_TIMEOUT_MS]。
     *
     * Telephony reconfiguration legitimately drops and re-establishes the data connection, so a
     * verdict taken the instant cleanup finishes is meaningless. Poll until healthy, and only
     * report the final sample if it never recovers.
     */
    fun awaitHealthy(context: Context, subId: Int): DataHealth {
        var waited = 0L
        var last = DataHealth.UNKNOWN
        while (waited < SETTLE_TIMEOUT_MS) {
            last = healthOf(DiagnosticCollector.collectAll(context, subId))
            if (last == DataHealth.HEALTHY) return last
            Thread.sleep(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
        }
        return last
    }
}
