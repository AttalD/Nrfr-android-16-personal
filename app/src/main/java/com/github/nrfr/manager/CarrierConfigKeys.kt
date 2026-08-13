package com.github.nrfr.manager

import android.telephony.CarrierConfigManager as AndroidCarrierConfigManager

/**
 * [OverrideSpec] → carrier config 键值映射。
 *
 * Kept free of any Android *runtime* dependency so it can be unit tested on the JVM: the three
 * `KEY_…` symbols are `public static final String` compile-time constants and are inlined by the
 * compiler, so no Android class is ever loaded here.
 */
object CarrierConfigKeys {

    /**
     * `UiccProfile.handleSimCountryIsoOverride` copies this into the
     * `gsm.sim.operator.iso-country` system property, which is what
     * `TelephonyManager.getSimCountryIso()` returns to ordinary apps.
     */
    const val KEY_SIM_COUNTRY_ISO =
        AndroidCarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING

    const val KEY_CARRIER_NAME_OVERRIDE =
        AndroidCarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL

    const val KEY_CARRIER_NAME =
        AndroidCarrierConfigManager.KEY_CARRIER_NAME_STRING

    /** Values to publish for [spec]; empty when nothing should be overridden. */
    fun forSpec(spec: OverrideSpec): Map<String, Any> = buildMap {
        spec.countryIso?.let { put(KEY_SIM_COUNTRY_ISO, it) }
        spec.carrierName?.let {
            put(KEY_CARRIER_NAME_OVERRIDE, true)
            put(KEY_CARRIER_NAME, it)
        }
    }

    /** Inverse of [forSpec], used to render whatever the framework currently reports. */
    fun toSpec(values: Map<String, Any?>): OverrideSpec {
        val country = values[KEY_SIM_COUNTRY_ISO] as? String
        val nameOverridden = values[KEY_CARRIER_NAME_OVERRIDE] as? Boolean ?: false
        val name = (values[KEY_CARRIER_NAME] as? String)?.takeIf { nameOverridden }
        return OverrideSpec(
            countryIso = country?.takeIf { it.isNotBlank() },
            carrierName = name?.takeIf { it.isNotBlank() }
        )
    }
}
