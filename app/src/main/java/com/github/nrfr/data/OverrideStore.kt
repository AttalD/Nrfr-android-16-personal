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

    fun clear(context: Context, subId: Int) = put(context, subId, OverrideSpec())

    /** Subscription ids that currently have a non-empty override, used on boot re-apply. */
    fun configuredSubIds(context: Context): List<Int> =
        prefs(context).getStringSet(KEY_SUBS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
}
