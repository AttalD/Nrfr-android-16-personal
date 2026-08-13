package com.github.nrfr.manager

/**
 * 与设备无关的覆盖描述：某个 subId 上希望对外呈现的运营商信息。
 *
 * This class intentionally contains **no Android dependencies** so that all of its
 * validation/normalisation logic can be unit tested on the JVM without a device.
 */
data class OverrideSpec(
    /** Lower-case 2 letter ISO country, e.g. "jp". Null means "do not override". */
    val countryIso: String? = null,
    /** Displayed carrier name, e.g. "NTT DOCOMO". Null means "do not override". */
    val carrierName: String? = null,
    /**
     * Advanced / opt-in: MCC+MNC reported by [android.telephony.TelephonyManager.getSimOperator],
     * e.g. "44010". Null means "keep the real value of the SIM".
     *
     * Changing this alters the operator numeric the *framework* uses for APN matching, so it is
     * off by default — see [CarrierConfigManager] docs.
     */
    val simOperatorNumeric: String? = null
) {
    val isEmpty: Boolean
        get() = countryIso == null && carrierName == null && simOperatorNumeric == null

    /** Human readable summary used by the UI ("当前配置" card). */
    fun describe(): Map<String, String> = buildMap {
        countryIso?.let { put(KEY_COUNTRY, it.uppercase()) }
        carrierName?.let { put(KEY_CARRIER, it) }
        simOperatorNumeric?.let { put(KEY_MCCMNC, it) }
    }

    companion object {
        const val KEY_COUNTRY = "国家码"
        const val KEY_CARRIER = "运营商名称"
        const val KEY_MCCMNC = "SIM 运营商代码"

        /**
         * Builds a spec from raw UI input, normalising and rejecting malformed values.
         *
         * - country code must be exactly two ASCII letters; stored lower-case
         * - carrier name is trimmed; blank is treated as "not set"
         * - MCC+MNC must be 5 or 6 digits
         */
        fun of(
            countryCode: String?,
            carrierName: String?,
            simOperatorNumeric: String? = null
        ): OverrideSpec = OverrideSpec(
            countryIso = normalizeCountry(countryCode),
            carrierName = carrierName?.trim()?.takeIf { it.isNotEmpty() },
            simOperatorNumeric = normalizeMccMnc(simOperatorNumeric)
        )

        fun normalizeCountry(raw: String?): String? {
            val v = raw?.trim().orEmpty()
            if (v.length != 2) return null
            if (!v.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
            return v.lowercase()
        }

        fun normalizeMccMnc(raw: String?): String? {
            val v = raw?.trim().orEmpty()
            if (v.length !in 5..6) return null
            if (!v.all { it.isDigit() }) return null
            return v
        }
    }
}
