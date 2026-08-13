package com.github.nrfr.diag

/**
 * 「只改 SIM 国家码」实验的结论分类。
 *
 * The whole point of separating these: the sentinel probe proved the framework *transports* our
 * bundle, which says nothing about whether it *acts on*
 * `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING` specifically. An OEM build could accept and merge the key
 * and still never call `UiccProfile.handleSimCountryIsoOverride()`.
 */
enum class OverrideVerdict(val label: String) {
    /** 实验未执行。 */
    NOT_RUN("未执行"),

    /** A 失败：键根本没进入合并后的 CarrierConfig。 */
    CONFIG_REJECTED("❌ CarrierConfig 未接受该键"),

    /**
     * A 成功、B 失败：配置进去了，但 `getSimCountryIso()` 没变。
     * 说明本 ROM 不响应这个键 —— 需要如实报告，而不是换别的机制去试。
     */
    CONFIG_ACCEPTED_NO_EFFECT("⚠️ 配置已接受，但 getSimCountryIso() 未改变"),

    /** A、B 均成功。 */
    EFFECTIVE("✅ 覆盖生效：getSimCountryIso() 已改变")
}

/**
 * 实验期间**允许**发生变化的键。其余任何身份键的变化都算意外副作用。
 */
val EXPECTED_CHANGE_KEYS = setOf("sim_country_iso")

data class CountryOverrideResult(
    val requestedCountry: String,
    val steps: List<ProbeStep>,
    /** A：哨兵之外，真实的国家码键是否出现在合并后的 CarrierConfig 中。 */
    val configKeyPresent: Boolean = false,
    val configKeyValue: String? = null,
    val simCountryBefore: String? = null,
    val simCountryDuring: String? = null,
    val simCountryAfter: String? = null,
    /** 实验前 → 实验中 的逐项对比。 */
    val comparisonsDuring: List<ValueComparison> = emptyList(),
    /** 实验前 → 还原后 的逐项对比（还原是否干净）。 */
    val comparisonsAfter: List<ValueComparison> = emptyList()
) {
    /** A */
    val overrideAccepted: Boolean get() = configKeyPresent

    /** B —— 只有两次都读到且确实不同才算变了。 */
    val simCountryChanged: Boolean
        get() = simCountryBefore != null && simCountryDuring != null &&
                !simCountryBefore.equals(simCountryDuring, ignoreCase = true)

    /** 是否变成了我们请求的那个国家（而不是变成了别的东西）。 */
    val simCountryMatchesRequest: Boolean
        get() = simCountryDuring?.equals(requestedCountry, ignoreCase = true) == true

    val verdict: OverrideVerdict
        get() = when {
            steps.isEmpty() -> OverrideVerdict.NOT_RUN
            !overrideAccepted -> OverrideVerdict.CONFIG_REJECTED
            simCountryChanged && simCountryMatchesRequest -> OverrideVerdict.EFFECTIVE
            else -> OverrideVerdict.CONFIG_ACCEPTED_NO_EFFECT
        }

    /** C：网络国家码必须保持不变。 */
    val networkCountryHeld: Boolean get() = held("network_country_iso")

    /** D：SIM MCC/MNC 必须保持不变。 */
    val simOperatorHeld: Boolean get() = held("sim_operator")

    val networkOperatorHeld: Boolean get() = held("network_operator")
    val carrierIdHeld: Boolean get() = held("sim_carrier_id")
    val apnHeld: Boolean get() = held("apn")
    val roamingHeld: Boolean get() = held("network_roaming")

    private fun held(key: String): Boolean =
        comparisonsDuring.firstOrNull { it.key == key }
            ?.outcome != ComparisonOutcome.CHANGED

    /**
     * 实验期间除了 SIM 国家码之外，是否有任何身份键发生了变化。
     */
    val unexpectedSideEffects: List<ValueComparison>
        get() = comparisonsDuring.filter { it.isMutation && it.key !in EXPECTED_CHANGE_KEYS }

    /** 还原后是否所有身份键（含 SIM 国家码）都回到了原值。 */
    val revertRestored: Boolean get() = comparisonsAfter.none { it.isMutation }

    val revertFailures: List<ValueComparison> get() = comparisonsAfter.filter { it.isMutation }

    /** 整个实验是否达到了「只改了国家码、别的都没动、且已还原」的理想结果。 */
    val cleanSuccess: Boolean
        get() = verdict == OverrideVerdict.EFFECTIVE &&
                unexpectedSideEffects.isEmpty() &&
                revertRestored
}
