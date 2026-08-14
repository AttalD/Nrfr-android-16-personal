package com.github.nrfr.region

/**
 * 想要对外呈现的地区身份。
 *
 * Only the fields we can actually influence appear here — there is no `networkCountryIso` field,
 * because offering one would imply a capability that does not exist without root.
 */
data class RegionalProfile(
    val name: String,
    /** SIM 国家码，如 "us"。null = 不改。 */
    val countryIso: String? = null,
    /** SIM 运营商显示名。null = 不改。 */
    val operatorName: String? = null,
    /**
     * SIM MCC+MNC，如 "310260"。null = 不改。
     *
     * Experimental and off by default: it is the one field that can plausibly disturb APN
     * matching and therefore mobile data.
     */
    val operatorNumeric: String? = null
) {
    val isEmpty: Boolean get() = countryIso == null && operatorName == null && operatorNumeric == null

    /** 本 profile 会触及哪些信号。 */
    fun touchedSignals(): Set<Signal> = buildSet {
        if (countryIso != null) add(Signal.SIM_COUNTRY_ISO)
        if (operatorName != null) add(Signal.SIM_OPERATOR_NAME)
        if (operatorNumeric != null) {
            add(Signal.SIM_OPERATOR_NUMERIC)
            // Carrier ID is derived from MCC/MNC, so it moves whether we want it to or not.
            add(Signal.SIM_CARRIER_ID)
        }
    }

    /**
     * 应用阶段允许发生变化的信号键 —— 用来把"预期的改动"与"意外副作用"区分开。
     *
     * Note this is the **apply-phase** set only. Cleanup deliberately compares against an empty
     * expected set, because after rollback nothing at all may differ from the baseline.
     */
    fun expectedChangeKeys(): Set<String> = buildSet {
        addAll(touchedSignals().map { it.key })
        // APN selection is matched on the SIM operator numeric (see CarrierResolver / the APN
        // database), so changing MCC/MNC legitimately reselects the APN. Expected while the
        // override is live; it must still come back afterwards.
        if (operatorNumeric != null) add(Signal.APN_DATA.key)
    }

    /** 本 profile 是否涉及尚未在真机验证的机制。 */
    fun usesExperimentalMechanism(): Boolean =
        touchedSignals().any { SignalCapabilities[it].status == SignalStatus.EXPERIMENTAL }

    fun validate(): List<String> = buildList {
        countryIso?.let {
            if (!it.matches(Regex("[a-z]{2}"))) add("国家码必须是 2 位小写字母：$it")
        }
        operatorNumeric?.let {
            if (!it.matches(Regex("\\d{5,6}"))) add("MCC+MNC 必须是 5-6 位数字：$it")
        }
        operatorName?.let {
            if (it.isBlank()) add("运营商名称不能为空")
        }
    }

    companion object {
        /** 只改国家码 —— 已验证机制，风险最低。 */
        fun countryOnly(iso: String, name: String = "仅国家码 ${iso.uppercase()}") =
            RegionalProfile(name = name, countryIso = iso.lowercase())

        val PRESETS: List<RegionalProfile> = listOf(
            countryOnly("us", "美国（仅国家码）"),
            countryOnly("jp", "日本（仅国家码）"),
            RegionalProfile(
                name = "美国 T-Mobile（含 MCC/MNC · 实验性）",
                countryIso = "us", operatorName = "T-Mobile", operatorNumeric = "310260"
            ),
            RegionalProfile(
                name = "日本 NTT docomo（含 MCC/MNC · 实验性）",
                countryIso = "jp", operatorName = "NTT DOCOMO", operatorNumeric = "44010"
            )
        )
    }
}
