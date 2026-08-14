package com.github.nrfr.service

import android.os.Build
import android.os.PersistableBundle
import android.service.carrier.CarrierIdentifier
import android.service.carrier.CarrierService
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.github.nrfr.data.OverrideStore
import com.github.nrfr.diag.CarrierServiceBridge
import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.OverrideSpec

/**
 * 本应用作为 CarrierService 向系统提供 carrier config。
 *
 * This is the Android 16 replacement for `ICarrierConfigLoader.overrideConfig`. Rather than poking
 * an override into the loader from outside — which the 2025-10 patch forbids for the shell UID —
 * we become the carrier config *provider* for the subscription, and the framework asks us through
 * the normal, supported extension point what the config should be.
 *
 * The system only binds this service while `ITelephony.setCarrierServicePackageOverride` names us
 * **and** we hold carrier privileges; both are arranged by
 * [com.github.nrfr.manager.CarrierConfigManager].
 */
class NrfrCarrierService : CarrierService() {

    /** API 33+ hands us the subscription directly — this is the path used on Android 16. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onLoadConfig(subscriptionId: Int, id: CarrierIdentifier?): PersistableBundle =
        buildConfig(subscriptionId)

    /**
     * Legacy path for API < 33, where the subscription is not passed in.
     * Abstract in [CarrierService], so it must be implemented even though Android 16 uses the
     * two-argument overload above.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLoadConfig(id: CarrierIdentifier?): PersistableBundle =
        buildConfig(legacySubId())

    private fun buildConfig(subscriptionId: Int): PersistableBundle {
        CarrierServiceBridge.onLoadConfigCalled(subscriptionId)

        // Diagnostics probe: serve only the sentinel token, so the round trip can be proven
        // without putting any real telephony value into the config.
        // Highest precedence: a restore in progress must win over everything else, including a
        // persisted user override, or cleanup could never push the original value back.
        CarrierServiceBridge.restoreCountryIso?.let { iso ->
            Log.i(TAG, "onLoadConfig(subId=$subscriptionId) -> RESTORE country=$iso")
            return PersistableBundle().apply {
                putString(CarrierConfigKeys.KEY_SIM_COUNTRY_ISO, iso)
            }
        }

        CarrierServiceBridge.probeToken?.let { token ->
            Log.i(TAG, "onLoadConfig(subId=$subscriptionId) -> PROBE token")
            return PersistableBundle().apply {
                putString(CarrierServiceBridge.PROBE_KEY, token)
            }
        }

        // Transaction mode: exactly the keys this profile asks for, nothing else.
        // MCC/MNC is deliberately absent — no CarrierConfig key exists for it, so it travels via
        // setCarrierTestOverride instead.
        CarrierServiceBridge.activeProfile?.let { profile ->
            Log.i(TAG, "onLoadConfig(subId=$subscriptionId) -> PROFILE ${profile.name}")
            return toBundle(
                OverrideSpec(countryIso = profile.countryIso, carrierName = profile.operatorName)
            )
        }

        val spec = runCatching { OverrideStore.get(this, subscriptionId) }
            .getOrElse {
                Log.e(TAG, "failed to read override store for subId=$subscriptionId", it)
                OverrideSpec()
            }
        Log.i(TAG, "onLoadConfig(subId=$subscriptionId) -> $spec")
        return toBundle(spec)
    }

    /**
     * On API < 33 the callback carries no subscription id. Prefer the single configured
     * subscription when there is exactly one, otherwise fall back to the default subscription.
     */
    private fun legacySubId(): Int {
        val configured = runCatching { OverrideStore.configuredSubIds(this) }.getOrDefault(emptyList())
        return configured.singleOrNull()
            ?: runCatching { SubscriptionManager.getDefaultSubscriptionId() }
                .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }

    companion object {
        private const val TAG = "Nrfr/CarrierService"

        fun toBundle(spec: OverrideSpec): PersistableBundle {
            val bundle = PersistableBundle()
            for ((key, value) in CarrierConfigKeys.forSpec(spec)) {
                when (value) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                }
            }
            return bundle
        }
    }
}
