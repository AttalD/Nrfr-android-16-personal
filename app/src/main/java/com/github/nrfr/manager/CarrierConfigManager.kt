package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.github.nrfr.data.OverrideStore
import com.github.nrfr.model.SimCardInfo
import com.github.nrfr.service.NrfrCarrierService

/** 生效方式。 */
enum class Strategy {
    /**
     * `ICarrierConfigLoader.overrideConfig` — Nrfr 的原始方案。
     * Works up to the 2025-09 security patch level; blocked for the shell UID after that.
     */
    LEGACY_OVERRIDE,

    /**
     * 把本应用注册为该 SIM 的 CarrierService，由系统主动向我们索取配置。
     * The supported extension point, and the only non-root route left on Android 16.
     */
    CARRIER_SERVICE
}

sealed class ApplyResult {
    data class Success(val strategy: Strategy, val note: String? = null) : ApplyResult()
    data class Failure(val kind: FailureKind, val detail: String?) : ApplyResult() {
        val message: String get() = TelephonyFailures.describe(kind) + (detail?.let { "\n($it)" } ?: "")
    }
}

/**
 * Nrfr 的核心：在不同 Android 版本上以合适的机制覆盖 SIM 的国家码/运营商名。
 *
 * ## Why there are two strategies
 *
 * The original implementation called `ICarrierConfigLoader.overrideConfig()` over Binder while
 * running as the shell UID (courtesy of Shizuku). AOSP commit `1ac1e79d1` "Protect shell
 * overriding the carrier config" (bug 441823943, shipped in the 2025-10 security patch, tracked as
 * CVE-2025-48617) added `CarrierConfigLoader.secureOverrideConfig`, which starts with:
 *
 * ```java
 * if (TelephonyPermissions.isShell(getCallingUid())) {
 *     throw new SecurityException("overrideConfig cannot be invoked by shell");
 * }
 * ```
 *
 * A companion commit (`c8123b01b`) additionally refuses `persistent=true` unless the caller is a
 * system app on a user build. Both apply to Android 16 / OxygenOS 16, so the legacy path is
 * simply unavailable there — there is nothing to "work around" inside `overrideConfig` itself.
 *
 * [Strategy.CARRIER_SERVICE] therefore stops trying to push config in from outside and instead
 * becomes the component the framework *pulls* config from:
 *
 *  1. `ITelephony.setCarrierTestOverride(subId, …, carrierPrivilegeRules = <our cert SHA-256>, …)`
 *     grants this package carrier privileges for the subscription. It is guarded by
 *     `enforceModifyPermission()` only — shell holds MODIFY_PHONE_STATE and the CVE fix does not
 *     touch it.
 *  2. `ITelephony.setCarrierServicePackageOverride(subId, <us>, …)` pins us as the carrier service.
 *     It is guarded by `TelephonyPermissions.enforceShellOnly`, i.e. it *requires* the shell UID —
 *     exactly what Shizuku provides.
 *  3. The framework binds [NrfrCarrierService] and calls `onLoadConfig`, and
 *     `UiccProfile.handleSimCountryIsoOverride` applies the country ISO we return.
 */
object CarrierConfigManager {

    private const val TAG = "Nrfr/Manager"

    /** Cached result of probing the legacy path, so we do not retry a known-blocked call. */
    @Volatile
    private var legacyBlocked: Boolean? = null

    // ------------------------------------------------------------------ read

    fun getSimCards(context: Context): List<SimCardInfo> {
        val simCards = mutableListOf<SimCardInfo>()
        for (slot in 0..1) {
            @Suppress("DEPRECATION")
            val subIds = runCatching { SubscriptionManager.getSubId(slot) }.getOrNull() ?: continue
            val subId = subIds.firstOrNull() ?: continue
            if (!SubscriptionManager.isValidSubscriptionId(subId)) continue
            simCards.add(
                SimCardInfo(
                    slot = slot + 1,
                    subId = subId,
                    carrierName = carrierNameFor(context, subId),
                    currentConfig = describeCurrent(context, subId)
                )
            )
        }
        return simCards
    }

    /**
     * What is actually in effect right now. Prefers the framework's own view, and falls back to
     * whatever we persisted if the privileged read is unavailable.
     */
    private fun describeCurrent(context: Context, subId: Int): Map<String, String> {
        val live = runCatching {
            val config = PrivilegedTelephony.currentConfig(subId, context.packageName)
            config?.let { CarrierConfigKeys.toSpec(it.toValueMap()) }
        }.getOrNull()

        val stored = runCatching { OverrideStore.get(context, subId) }.getOrDefault(OverrideSpec())
        val effective = when {
            live != null && !live.isEmpty -> live.copy(simOperatorNumeric = stored.simOperatorNumeric)
            else -> stored
        }
        return effective.describe()
    }

    private fun PersistableBundle.toValueMap(): Map<String, Any?> =
        keySet().associateWith { @Suppress("DEPRECATION") get(it) }

    private fun carrierNameFor(context: Context, subId: Int): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tm.createForSubscriptionId(subId).networkOperatorName
            } else {
                @Suppress("DEPRECATION")
                tm.networkOperatorName
            }
        }.getOrElse { runCatching { tm.networkOperatorName }.getOrDefault("") }
    }

    // ----------------------------------------------------------------- write

    fun setCarrierConfig(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String?,
        simOperatorNumeric: String? = null
    ): ApplyResult = apply(
        context,
        subId,
        OverrideSpec.of(countryCode, carrierName, simOperatorNumeric)
    )

    fun apply(context: Context, subId: Int, spec: OverrideSpec): ApplyResult {
        if (spec.isEmpty) return revert(context, subId)

        // Persist first: the framework may call back into NrfrCarrierService as soon as we
        // register, and the store is what that callback reads.
        OverrideStore.put(context, subId, spec)

        if (legacyBlocked != true) {
            when (val r = tryLegacy(subId, spec)) {
                is ApplyResult.Success -> {
                    legacyBlocked = false
                    return r
                }

                is ApplyResult.Failure -> {
                    if (!r.kind.shouldFallBackToCarrierService) {
                        OverrideStore.clear(context, subId)
                        return r
                    }
                    legacyBlocked = true
                    Log.i(TAG, "legacy overrideConfig unavailable (${r.kind}); using CarrierService")
                }
            }
        }

        val r = tryCarrierService(context, subId, spec)
        if (r is ApplyResult.Failure) OverrideStore.clear(context, subId)
        return r
    }

    private fun tryLegacy(subId: Int, spec: OverrideSpec): ApplyResult = runCatching {
        PrivilegedTelephony.overrideConfig(subId, NrfrCarrierService.toBundle(spec), true)
        ApplyResult.Success(Strategy.LEGACY_OVERRIDE)
    }.getOrElse { t ->
        Log.w(TAG, "legacy overrideConfig failed", t)
        ApplyResult.Failure(TelephonyFailures.classify(t), t.message)
    }

    private fun tryCarrierService(context: Context, subId: Int, spec: OverrideSpec): ApplyResult {
        val certs = PrivilegedTelephony.ownCertSha256(context)
        if (certs.isEmpty()) {
            return ApplyResult.Failure(
                FailureKind.MISSING_API,
                "无法读取本应用的签名证书哈希"
            )
        }

        // Read the SIM's real identity *before* touching anything, so we can hand it straight back
        // to setCarrierTestOverride and leave gsm.sim.operator.* untouched.
        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        val mccMnc = spec.simOperatorNumeric ?: realMccMnc

        return runCatching {
            PrivilegedTelephony.applyCarrierPrivileges(
                subId = subId,
                certSha256Hex = certs.first(),
                realMccMnc = mccMnc,
                realSpn = spec.carrierName ?: realSpn
            )
            PrivilegedTelephony.setCarrierServicePackageOverride(
                subId = subId,
                carrierServicePackage = context.packageName,
                callingPackage = context.packageName
            )
            runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
                .onFailure { Log.w(TAG, "notifyConfigChanged failed (non-fatal)", it) }

            ApplyResult.Success(
                Strategy.CARRIER_SERVICE,
                note = "已通过 CarrierService 生效" +
                        if (spec.simOperatorNumeric != null) "（含 MCC/MNC 伪装）" else ""
            )
        }.getOrElse { t ->
            Log.e(TAG, "CarrierService strategy failed", t)
            ApplyResult.Failure(TelephonyFailures.classify(t), t.message)
        }
    }

    // ---------------------------------------------------------------- revert

    fun resetCarrierConfig(context: Context, subId: Int): ApplyResult = revert(context, subId)

    /**
     * Undoes everything this app did for [subId]. Safe to call even if nothing was applied.
     *
     * Note the residual state: `IccRecords`' "test mode" flag stays set until the SIM is
     * re-initialised (reboot or SIM re-insert). That is inert on its own — every
     * `CarrierTestOverride` getter falls back to the real SIM value when its override is null,
     * and we restore the real MCC/MNC and SPN explicitly here.
     */
    fun revert(context: Context, subId: Int): ApplyResult {
        // Clear the store first: if the framework calls NrfrCarrierService while we unwind, it
        // must get an empty bundle rather than the old override.
        val previous = runCatching { OverrideStore.get(context, subId) }.getOrDefault(OverrideSpec())
        OverrideStore.clear(context, subId)

        val errors = mutableListOf<String>()

        runCatching {
            PrivilegedTelephony.setCarrierServicePackageOverride(subId, null, context.packageName)
        }.onFailure { errors += "carrierServiceOverride: ${it.message}" }

        // Restore the SIM's own operator numeric / name, undoing any spoof.
        val realMccMnc = PrivilegedTelephony.realMccMnc(context, subId)
        val realSpn = PrivilegedTelephony.realSpn(context, subId)
        runCatching {
            PrivilegedTelephony.clearCarrierPrivileges(subId, realMccMnc, realSpn)
        }.onFailure { errors += "carrierPrivileges: ${it.message}" }

        // Also drop any legacy override that an older Android may still be holding.
        runCatching { PrivilegedTelephony.overrideConfig(subId, null, true) }
            .onFailure { Log.d(TAG, "legacy reset skipped: ${it.message}") }

        runCatching { PrivilegedTelephony.notifyConfigChanged(subId) }
            .onFailure { errors += "notifyConfigChanged: ${it.message}" }

        return if (errors.isEmpty()) {
            ApplyResult.Success(
                strategy = if (legacyBlocked == false) Strategy.LEGACY_OVERRIDE else Strategy.CARRIER_SERVICE,
                note = if (previous.isEmpty) "无需还原" else "已还原"
            )
        } else {
            ApplyResult.Failure(FailureKind.UNKNOWN, errors.joinToString("; "))
        }
    }

    // -------------------------------------------------------- boot re-apply

    /**
     * Re-applies persisted overrides, e.g. after a reboot.
     *
     * Both privileged calls this depends on are in-memory framework state that does not survive a
     * reboot, and Shizuku itself must be running again first — so this is best-effort and is
     * driven from the app/boot receiver rather than assumed to be automatic.
     */
    fun reapplyAll(context: Context): Map<Int, ApplyResult> =
        OverrideStore.configuredSubIds(context).associateWith { subId ->
            apply(context, subId, OverrideStore.get(context, subId))
        }
}
