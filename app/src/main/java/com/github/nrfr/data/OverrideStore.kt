package com.github.nrfr.data

import android.content.Context
import com.github.nrfr.manager.OverrideSpec

/**
 * 覆盖配置的持久化存储。
 *
 * Two consumers read this:
 *  - the UI, to show what is currently configured;
 *  - [com.github.nrfr.service.NrfrCarrierService], which the *system* binds and asks for the
 *    carrier config bundle. That makes the store the single source of truth for what we report.
 */
object OverrideStore {

    private const val PREFS = "nrfr_overrides"
    private const val KEY_COUNTRY = "country_"
    private const val KEY_CARRIER = "carrier_"
    private const val KEY_MCCMNC = "mccmnc_"
    private const val KEY_ORIGINAL_COUNTRY = "orig_country_"
    private const val KEY_SUBS = "subs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, subId: Int): OverrideSpec {
        val p = prefs(context)
        return OverrideSpec(
            countryIso = p.getString("$KEY_COUNTRY$subId", null),
            carrierName = p.getString("$KEY_CARRIER$subId", null),
            simOperatorNumeric = p.getString("$KEY_MCCMNC$subId", null)
        )
    }

    fun put(context: Context, subId: Int, spec: OverrideSpec) {
        val p = prefs(context)
        val subs = p.getStringSet(KEY_SUBS, emptySet()).orEmpty().toMutableSet()
        p.edit().apply {
            if (spec.countryIso != null) {
                putString("$KEY_COUNTRY$subId", spec.countryIso)
            } else {
                remove("$KEY_COUNTRY$subId")
            }
            if (spec.carrierName != null) {
                putString("$KEY_CARRIER$subId", spec.carrierName)
            } else {
                remove("$KEY_CARRIER$subId")
            }
            if (spec.simOperatorNumeric != null) {
                putString("$KEY_MCCMNC$subId", spec.simOperatorNumeric)
            } else {
                remove("$KEY_MCCMNC$subId")
            }
            if (spec.isEmpty) subs.remove(subId.toString()) else subs.add(subId.toString())
            putStringSet(KEY_SUBS, subs)
        }.apply()
    }

    /**
     * 记录**首次**覆盖之前的真实 SIM 国家码。
     *
     * Required because the framework has no un-apply path for
     * `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`: removing the key leaves
     * `gsm.sim.operator.iso-country` at whatever was last written, so reverting means actively
     * pushing this value back. Only written once, so re-applying a new country never overwrites
     * the genuine original.
     */
    fun rememberOriginalCountry(context: Context, subId: Int, iso: String?) {
        if (iso.isNullOrBlank()) return
        val p = prefs(context)
        val key = "$KEY_ORIGINAL_COUNTRY$subId"
        if (p.contains(key)) return
        p.edit().putString(key, iso.lowercase()).apply()
    }

    fun originalCountry(context: Context, subId: Int): String? =
        prefs(context).getString("$KEY_ORIGINAL_COUNTRY$subId", null)

    private fun forgetOriginalCountry(context: Context, subId: Int) {
        prefs(context).edit().remove("$KEY_ORIGINAL_COUNTRY$subId").apply()
    }

    fun clear(context: Context, subId: Int) {
        put(context, subId, OverrideSpec())
        forgetOriginalCountry(context, subId)
    }

    /** Subscription ids that currently have a non-empty override, used on boot re-apply. */
    fun configuredSubIds(context: Context): List<Int> =
        prefs(context).getStringSet(KEY_SUBS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
}
