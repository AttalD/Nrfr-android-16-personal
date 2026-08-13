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
 * The sentinel probe proved the framework binds us, calls `onLoadConfig()` and merges whatever we
 * return. It proved nothing about whether this particular ROM *acts on*
 * `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`. In AOSP, `UiccProfile.handleSimCountryIsoOverride()`
 * copies that key into the `gsm.sim.operator.iso-country` property that backs
 * `TelephonyManager.getSimCountryIso()` — but an OEM build could merge the key and never run that
 * path. So the two questions are kept strictly separate:
 *
 *  - **A** 配置被接受：the key is readable back from the merged CarrierConfig
 *  - **B** 配置真的生效：`getSimCountryIso()` actually moved
 *
 * ## 明确不做的事
 *
 * The served bundle contains **exactly one key**. No carrier-name override, and in particular no
 * MCC/MNC: `setCarrierTestOverride` is called with the SIM's **real** MCC/MNC and SPN, exactly as
 * in the probe, so `gsm.sim.operator.numeric` / `.alpha` keep their values.
 */
object CountryOverrideExperiment {

    private const val TAG = "Nrfr/Experiment"
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
        steps += ProbeStep(
            "采集实验前基线", true,
            "SIM 国家码=${simCountryBefore ?: "(不可读)"} " +
                    "网络国家码=${before.readable("network_country_iso") ?: "(不可读)"} " +
                    "SIM MCC/MNC=${before.readable("sim_operator") ?: "(不可读)"} " +
                    "CarrierID=${before.readable("sim_carrier_id") ?: "(不可读)"}"
        )

        // ---- 1. safety precondition ---------------------------------------------------
        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }.getOrNull()
        if (!existing.isNullOrBlank() && existing != context.packageName) {
            steps += ProbeStep(
                "安全前置检查", false,
                "已有其它应用被绑定为 CarrierService ($existing)，已中止"
            )
            return CountryOverrideResult(iso, steps, simCountryBefore = simCountryBefore)
        }
        steps += ProbeStep("安全前置检查", true, "当前无其它 CarrierService 绑定")

        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            steps += ProbeStep("读取本应用签名哈希", false, "无法读取签名证书")
            return CountryOverrideResult(iso, steps, simCountryBefore = simCountryBefore)
        }

        // Real values, handed straight back so the two properties they drive do not move.
        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        steps += ProbeStep(
            "回填 SIM 真实身份", true,
            "MCC+MNC=${realMccMnc ?: "(空)"} SPN=${realSpn ?: "(空)"}（原样回填，不修改）"
        )

        CarrierServiceBridge.reset()
        CarrierServiceBridge.experimentCountryIso = iso

        var configKeyPresent = false
        var configKeyValue: String? = null
        var simCountryDuring: String? = null
        var during: List<DiagnosticValue> = emptyList()

        try {
            // ---- 2. register --------------------------------------------------------
            val grant = runCatching {
                PrivilegedTelephony.applyCarrierPrivileges(subId, certs.first(), realMccMnc, realSpn)
            }
            steps += ProbeStep("授予 carrier privileges", grant.isSuccess, grant.err())
            if (grant.isFailure) return finish(context, subId, iso, steps, before, simCountryBefore)

            val nominate = runCatching {
                PrivilegedTelephony.setCarrierServicePackageOverride(
                    subId, context.packageName, context.packageName
                )
            }
            steps += ProbeStep("注册为 CarrierService", nominate.isSuccess, nominate.err())
            if (nominate.isFailure) return finish(context, subId, iso, steps, before, simCountryBefore)

            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                .also { steps += ProbeStep("触发 CarrierConfig 重新加载", it.isSuccess, it.err()) }

            // ---- 3. (A) did the key land in the merged config? ----------------------
            configKeyPresent = waitFor {
                readConfigCountry(context, subId).also { configKeyValue = it } == iso
            }
            steps += ProbeStep(
                "A · CarrierConfig 已接受国家码键", configKeyPresent,
                "$KEY = ${configKeyValue ?: "(未出现)"}"
            )

            // ---- 4. (B) did getSimCountryIso() actually move? -----------------------
            val changed = waitFor {
                val v = DiagnosticCollector.collectAll(context, subId).readable("sim_country_iso")
                v != null && !v.equals(simCountryBefore ?: "", ignoreCase = true)
            }
            during = DiagnosticCollector.collectAll(context, subId)
            simCountryDuring = during.readable("sim_country_iso")
            steps += ProbeStep(
                "B · getSimCountryIso() 已改变", changed,
                "${simCountryBefore ?: "(不可读)"} → ${simCountryDuring ?: "(不可读)"}"
            )

            // ---- 5. (C)(D) everything else must have held ---------------------------
            val cmp = ReportFormatter.compare(before, during)
            val side = cmp.filter { it.isMutation && it.key !in EXPECTED_CHANGE_KEYS }
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

            return finish(
                context, subId, iso, steps, before, simCountryBefore,
                configKeyPresent, configKeyValue, simCountryDuring, cmp
            )
        } finally {
            // ---- 6. always revert ---------------------------------------------------
            CarrierServiceBridge.experimentCountryIso = null
            runCatching {
                PrivilegedTelephony.setCarrierServicePackageOverride(subId, null, context.packageName)
            }.onFailure { Log.e(TAG, "revert carrierServiceOverride failed", it) }
            runCatching {
                PrivilegedTelephony.clearCarrierPrivileges(subId, realMccMnc, realSpn)
            }.onFailure { Log.e(TAG, "revert carrierPrivileges failed", it) }
            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                .onFailure { Log.e(TAG, "revert notify failed", it) }
        }
    }

    private const val KEY = CarrierConfigKeys.KEY_SIM_COUNTRY_ISO

    private fun finish(
        context: Context,
        subId: Int,
        iso: String,
        steps: MutableList<ProbeStep>,
        before: List<DiagnosticValue>,
        simCountryBefore: String?,
        configKeyPresent: Boolean = false,
        configKeyValue: String? = null,
        simCountryDuring: String? = null,
        comparisonsDuring: List<ValueComparison> = emptyList()
    ): CountryOverrideResult {
        // Wait for the revert to land: the key should disappear from the merged config again.
        waitFor(8_000L) { readConfigCountry(context, subId) == null }
        // …and for the country to fall back.
        waitFor(8_000L) {
            DiagnosticCollector.collectAll(context, subId)
                .readable("sim_country_iso")
                ?.equals(simCountryBefore ?: "", ignoreCase = true) == true
        }

        val after = DiagnosticCollector.collectAll(context, subId)
        val simCountryAfter = after.readable("sim_country_iso")
        val comparisonsAfter = ReportFormatter.compare(before, after)
        val failures = comparisonsAfter.filter { it.isMutation }

        steps += ProbeStep(
            "已自动还原", failures.isEmpty(),
            if (failures.isEmpty()) "SIM 国家码已回到 ${simCountryAfter ?: "(不可读)"}，全部身份值与实验前一致"
            else "以下值未还原: ${failures.joinToString(", ") { "${it.key}(${it.before}→${it.after})" }}"
        )

        return CountryOverrideResult(
            requestedCountry = iso,
            steps = steps,
            configKeyPresent = configKeyPresent,
            configKeyValue = configKeyValue,
            simCountryBefore = simCountryBefore,
            simCountryDuring = simCountryDuring,
            simCountryAfter = simCountryAfter,
            comparisonsDuring = comparisonsDuring,
            comparisonsAfter = comparisonsAfter
        )
    }

    private fun readConfigCountry(context: Context, subId: Int): String? = runCatching {
        PrivilegedTelephony.currentConfig(subId, context.packageName)?.getString(KEY)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

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

    private fun Result<*>.err(): String? = exceptionOrNull()?.let { t ->
        val root = generateSequence(t) { it.cause }.last()
        "${root.javaClass.simpleName}: ${root.message}"
    }
}
