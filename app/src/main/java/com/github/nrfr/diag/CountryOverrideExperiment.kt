package com.github.nrfr.diag

import android.content.Context
import android.util.Log
import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.PrivilegedTelephony

/**
 * 受控实验：**只**改 SIM 国家码，别的一概不动。
 *
 * ## 这个实验要回答什么
 *
 * 哨兵探测只证明框架会**搬运**我们的 bundle，没有证明它会**响应**
 * `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`。AOSP 由
 * `UiccProfile.handleSimCountryIsoOverride()` 负责把它写进
 * `gsm.sim.operator.iso-country`，OEM 完全可能合并了键却从不走那条路径。所以：
 *
 *  - **A** 配置被接受：键能从合并后的 CarrierConfig 读回
 *  - **B** 配置真的生效：`getSimCountryIso()` 真的变了
 *
 * ## 生命周期（run #8 的两个顺序缺陷已修复）
 *
 * ```
 * 基线 → 注册 → 下发 us → 验证 cn→us → 【清理】 → 【清理后才测量】 → 验证已回到 cn
 * ```
 *
 * 两个曾经的缺陷：
 *  1. `finish()` 写在 `try` 的 return 里，因此"事后"数据其实是在 `finally` 清理**之前**采集的；
 *  2. 清理时先解除了 CarrierService 注册，框架便不再向我们索取配置，
 *     而单纯移除键对属性是 no-op（见 [CountryIsoRestore]），于是国家码卡在 `us`。
 *
 * 现在清理完整地发生在 `finally` 中，测量在 `try/finally` **之后**才进行。
 */
object CountryOverrideExperiment {

    private const val TAG = "Nrfr/Experiment"
    private const val KEY = CarrierConfigKeys.KEY_SIM_COUNTRY_ISO
    private const val WAIT_STEP_MS = 250L
    private const val WAIT_TOTAL_MS = 15_000L

    const val DEFAULT_TEST_COUNTRY = "us"

    fun run(
        context: Context,
        slot: Int,
        subId: Int,
        country: String = DEFAULT_TEST_COUNTRY
    ): CountryOverrideResult {
        val iso = country.trim().lowercase()
        val steps = mutableListOf<ProbeStep>()

        // ---- 0. baseline --------------------------------------------------------------
        val before = DiagnosticCollector.collectAll(context, subId)
        val simCountryBefore = before.readable("sim_country_iso")
        val originalConfigKey = CountryIsoRestore.readConfigCountry(context, subId)

        steps += ProbeStep(
            "采集实验前基线", true,
            "SIM 国家码=${simCountryBefore ?: "(不可读)"} " +
                    "网络国家码=${before.readable("network_country_iso") ?: "(不可读)"} " +
                    "SIM MCC/MNC=${before.readable("sim_operator") ?: "(不可读)"} " +
                    "CarrierID=${before.readable("sim_carrier_id") ?: "(不可读)"}"
        )
        steps += ProbeStep(
            "记录 CarrierConfig 原始键状态", true,
            "$KEY = ${originalConfigKey ?: "(不存在)"}"
        )

        fun abort() = CountryOverrideResult(
            requestedCountry = iso,
            steps = steps,
            simCountryBefore = simCountryBefore,
            simCountryAfter = simCountryBefore,
            originalConfigKey = originalConfigKey,
            postCleanupConfigKey = originalConfigKey,
            restoreOutcome = RestoreOutcome.ALREADY_CORRECT
        )

        // Without a baseline we could not restore afterwards, and assuming "cn" would be
        // fabricating state. Refuse rather than risk a one-way change.
        if (simCountryBefore == null) {
            steps += ProbeStep(
                "基线可用性检查", false,
                "实验前读不到 SIM 国家码，无法保证可还原，已中止"
            )
            return abort()
        }
        steps += ProbeStep("基线可用性检查", true, "可还原目标 = $simCountryBefore")

        // ---- 1. preconditions ---------------------------------------------------------
        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }.getOrNull()
        if (!existing.isNullOrBlank() && existing != context.packageName) {
            steps += ProbeStep("安全前置检查", false, "已有其它 CarrierService ($existing)，已中止")
            return abort()
        }
        steps += ProbeStep("安全前置检查", true, "当前无其它 CarrierService 绑定")

        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            steps += ProbeStep("读取本应用签名哈希", false, "无法读取签名证书")
            return abort()
        }

        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        steps += ProbeStep(
            "回填 SIM 真实身份", true,
            "MCC+MNC=${realMccMnc ?: "(空)"} SPN=${realSpn ?: "(空)"}（原样回填，不修改）"
        )

        var configKeyPresent = false
        var configKeyValue: String? = null
        var simCountryDuring: String? = null
        var comparisonsDuring: List<ValueComparison> = emptyList()
        var restoreOutcome = RestoreOutcome.ALREADY_CORRECT
        var registered = false
        var releaseResult: ReleaseResult? = null

        CarrierServiceBridge.reset()
        CarrierServiceBridge.experimentCountryIso = iso

        try {
            // ---- 2. register --------------------------------------------------------
            PrivilegedTelephony.applyCarrierPrivileges(subId, certs.first(), realMccMnc, realSpn)
            steps += ProbeStep("授予 carrier privileges", true)

            PrivilegedTelephony.setCarrierServicePackageOverride(
                subId, context.packageName, context.packageName
            )
            registered = true
            steps += ProbeStep("注册为 CarrierService", true)

            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                .also { steps += ProbeStep("触发 CarrierConfig 重新加载", it.isSuccess, it.err()) }

            // ---- 3. (A) did the key land in the merged config? ----------------------
            configKeyPresent = waitFor {
                CountryIsoRestore.readConfigCountry(context, subId)
                    .also { configKeyValue = it }
                    ?.equals(iso, ignoreCase = true) == true
            }
            steps += ProbeStep(
                "A · CarrierConfig 已接受国家码键", configKeyPresent,
                "$KEY = ${configKeyValue ?: "(未出现)"}"
            )

            // ---- 4. (B) did getSimCountryIso() actually move? -----------------------
            val changed = waitFor {
                CountryIsoRestore.readSimCountry(context, subId)
                    ?.equals(simCountryBefore, ignoreCase = true) == false
            }
            val during = DiagnosticCollector.collectAll(context, subId)
            simCountryDuring = during.readable("sim_country_iso")
            steps += ProbeStep(
                "B · getSimCountryIso() 已改变", changed,
                "$simCountryBefore → ${simCountryDuring ?: "(不可读)"}"
            )

            // ---- 5. (C)(D) everything else must have held ---------------------------
            comparisonsDuring = ReportFormatter.compare(before, during)
            val side = comparisonsDuring.filter { it.isMutation && it.key !in EXPECTED_CHANGE_KEYS }
            steps += ProbeStep(
                "C/D · 其余身份值保持不变", side.isEmpty(),
                if (side.isEmpty())
                    "网络国家码=${during.readable("network_country_iso")} " +
                            "SIM MCC/MNC=${during.readable("sim_operator")} " +
                            "网络 MCC/MNC=${during.readable("network_operator")} " +
                            "CarrierID=${during.readable("sim_carrier_id")} " +
                            "APN=${during.readable("apn")}"
                else "意外变化: ${side.joinToString(", ") { it.key }}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "experiment failed", t)
            steps += ProbeStep("实验过程异常", false, describe(t))
        } finally {
            // ---- 6. cleanup — unconditional, and in an order that actually works -----
            CarrierServiceBridge.experimentCountryIso = null

            if (registered) {
                // Push the baseline back *while still bound as CarrierService*. Dropping the key
                // alone is a no-op for the property; see CountryIsoRestore.
                restoreOutcome = runCatching {
                    CountryIsoRestore.restore(context, subId, simCountryBefore)
                }.getOrElse {
                    Log.e(TAG, "restore threw", it)
                    RestoreOutcome.FAILED
                }
                Log.i(TAG, "restore outcome=$restoreOutcome")
            }

            // Explicit, verified release: privileges first, then the override, then poll until
            // the framework actually reports us unbound (the recompute is asynchronous).
            releaseResult = runCatching {
                CarrierServiceRelease.release(context, slot, subId, realMccMnc, realSpn)
            }.getOrElse {
                Log.e(TAG, "release threw", it)
                ReleaseResult(
                    listOf(ProbeStep("释放 CarrierService", false, describe(it))),
                    CarrierServiceRelease.readBound(slot), released = false
                )
            }
            CarrierServiceBridge.reset()
        }

        // ---- 7. measure only AFTER cleanup has completed ----------------------------
        steps += ProbeStep(
            "清理动作", restoreOutcome != RestoreOutcome.FAILED,
            "还原方式=${restoreOutcome.label}（把基线值 $simCountryBefore 主动推回，再移除该键）"
        )

        releaseResult?.let { rel ->
            steps += rel.steps
            steps += ProbeStep(
                "CarrierService 已释放", rel.released,
                "框架报告绑定 = ${rel.boundPackageAfter ?: "(无)"}"
            )
        }

        val postCleanupConfigKey = CountryIsoRestore.readConfigCountry(context, subId)
        val after = DiagnosticCollector.collectAll(context, subId)
        val simCountryAfter = after.readable("sim_country_iso")
        val comparisonsAfter = ReportFormatter.compare(before, after)

        steps += ProbeStep(
            "清理后 CarrierConfig 键状态",
            originalConfigKey?.lowercase() == postCleanupConfigKey?.lowercase(),
            "$KEY = ${postCleanupConfigKey ?: "(不存在)"}（原始: ${originalConfigKey ?: "(不存在)"}）"
        )
        steps += ProbeStep(
            "清理后 getSimCountryIso()",
            simCountryAfter?.equals(simCountryBefore, ignoreCase = true) == true,
            "${simCountryAfter ?: "(不可读)"}（应为 $simCountryBefore）"
        )

        val failures = comparisonsAfter.filter { it.isMutation }
        steps += ProbeStep(
            "已完全还原", failures.isEmpty(),
            if (failures.isEmpty()) "全部身份值与实验前一致"
            else "未还原: ${failures.joinToString(", ") { "${it.key}(${it.before}→${it.after})" }}"
        )

        return CountryOverrideResult(
            requestedCountry = iso,
            steps = steps,
            configKeyPresent = configKeyPresent,
            configKeyValue = configKeyValue,
            simCountryBefore = simCountryBefore,
            simCountryDuring = simCountryDuring,
            simCountryAfter = simCountryAfter,
            originalConfigKey = originalConfigKey,
            postCleanupConfigKey = postCleanupConfigKey,
            restoreOutcome = restoreOutcome,
            carrierServiceReleased = releaseResult?.released ?: true,
            boundPackageAfter = releaseResult?.boundPackageAfter,
            comparisonsDuring = comparisonsDuring,
            comparisonsAfter = comparisonsAfter
        )
    }

    private fun List<DiagnosticValue>.readable(key: String): String? =
        firstOrNull { it.key == key }?.takeIf { it.error == null }?.value

    private fun waitFor(totalMs: Long = WAIT_TOTAL_MS, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (condition()) return true
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return condition()
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    private fun Result<*>.err(): String? = exceptionOrNull()?.let { describe(it) }
}
