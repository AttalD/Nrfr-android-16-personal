package com.github.nrfr.manager

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.telephony.TelephonyFrameworkInitializer
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ICarrierConfigLoader
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

/**
 * 通过 Shizuku 以 shell 身份访问 telephony 的隐藏 Binder 接口。
 *
 * All hidden `ITelephony` methods are reached reflectively rather than through a compile-time
 * AIDL stub: the signatures we need have been stable since Android 12, but OEM ROMs do reorder and
 * occasionally drop hidden methods, and reflection lets us fail with a clear
 * [FailureKind.MISSING_API] instead of a `NoSuchMethodError` at class-load time.
 */
object PrivilegedTelephony {

    private const val TAG = "Nrfr/Privileged"
    private const val ITELEPHONY = "com.android.internal.telephony.ITelephony"

    // ---------------------------------------------------------------- binders

    private fun carrierConfigBinder(): IBinder =
        TelephonyFrameworkInitializer
            .getTelephonyServiceManager()
            .carrierConfigServiceRegisterer
            .get() ?: throw IllegalStateException("carrier_config service unavailable")

    private fun telephonyBinder(): IBinder =
        TelephonyFrameworkInitializer
            .getTelephonyServiceManager()
            .telephonyServiceRegisterer
            .get() ?: throw IllegalStateException("phone service unavailable")

    fun carrierConfigLoader(): ICarrierConfigLoader =
        ICarrierConfigLoader.Stub.asInterface(ShizukuBinderWrapper(carrierConfigBinder()))

    /** `ITelephony` proxy backed by Shizuku, obtained reflectively. */
    private fun telephony(): Pair<Class<*>, Any> {
        val iface = Class.forName(ITELEPHONY)
        val stub = Class.forName("$ITELEPHONY\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val proxy = asInterface.invoke(null, ShizukuBinderWrapper(telephonyBinder()))
            ?: throw IllegalStateException("ITelephony.asInterface returned null")
        return iface to proxy
    }

    private fun method(iface: Class<*>, name: String, vararg params: Class<*>): Method =
        iface.getMethod(name, *params)

    // ------------------------------------------------- legacy overrideConfig

    /**
     * Pre-2025-10 path. Throws `SecurityException("overrideConfig cannot be invoked by shell")`
     * on patched Android 15/16 builds.
     */
    fun overrideConfig(subId: Int, bundle: PersistableBundle?, persistent: Boolean) {
        carrierConfigLoader().overrideConfig(subId, bundle, persistent)
    }

    @Suppress("DEPRECATION")
    fun currentConfig(subId: Int, callingPackage: String): PersistableBundle? =
        carrierConfigLoader().getConfigForSubId(subId, callingPackage)

    /**
     * Ask the framework to reload carrier config for [subId].
     *
     * `CarrierConfigLoader.notifyConfigChangedForSubId` only requires MODIFY_PHONE_STATE or
     * carrier privileges — it is *not* affected by the shell block — so shell may call it.
     */
    fun notifyConfigChanged(subId: Int) {
        carrierConfigLoader().notifyConfigChangedForSubId(subId)
    }

    // ---------------------------------------------- carrier service strategy

    /**
     * Grants carrier privileges for [subId] to whichever package carries [certSha256Hex].
     *
     * Reaches `CarrierPrivilegesTracker.setTestOverrideCarrierPrivilegeRules`, which makes
     * `getPackagePrivilegedStatus` return `PACKAGE_PRIVILEGED_FROM_SIM` for us. Requires only
     * MODIFY_PHONE_STATE (`PhoneInterfaceManager.setCarrierTestOverride` →
     * `enforceModifyPermission`), which the shell UID holds, and is untouched by the
     * CVE-2025-48617 hardening.
     *
     * [realMccMnc] / [realSpn] must be the SIM's *current* values (or a deliberate spoof):
     * `IccRecords.setCarrierTestOverride` writes them straight into the
     * `gsm.sim.operator.numeric` / `gsm.sim.operator.alpha` properties, so passing null there
     * would blank out `getSimOperator()`. Every other field is safe to leave null — the
     * `IccRecords` getters fall back to the real SIM value whenever the override is null.
     */
    fun applyCarrierPrivileges(
        subId: Int,
        certSha256Hex: String,
        realMccMnc: String?,
        realSpn: String?
    ) = setCarrierTestOverride(
        subId = subId,
        mccMnc = realMccMnc,
        spn = realSpn,
        carrierPrivilegeRules = CertHash.carrierPrivilegeRule(certSha256Hex)
    )

    /** Drops the privilege rules again, restoring the SIM's own values. */
    fun clearCarrierPrivileges(subId: Int, realMccMnc: String?, realSpn: String?) =
        setCarrierTestOverride(
            subId = subId,
            mccMnc = realMccMnc,
            spn = realSpn,
            carrierPrivilegeRules = null
        )

    private fun setCarrierTestOverride(
        subId: Int,
        mccMnc: String?,
        spn: String?,
        carrierPrivilegeRules: String?
    ) {
        val (iface, proxy) = telephony()
        val s = String::class.java
        val m = method(
            iface, "setCarrierTestOverride",
            Int::class.javaPrimitiveType!!, s, s, s, s, s, s, s, s, s
        )
        // subId, mccmnc, imsi, iccid, gid1, gid2, plmn(pnn), spn, carrierPrivilegeRules, apn
        m.invoke(proxy, subId, mccMnc, null, null, null, null, null, spn, carrierPrivilegeRules, null)
    }

    /**
     * Pins [carrierServicePackage] as the bound CarrierService for [subId], or resets the
     * override when null.
     *
     * `PhoneInterfaceManager.setCarrierServicePackageOverride` is guarded by
     * `TelephonyPermissions.enforceShellOnly` — it *requires* the shell UID, which is exactly
     * what Shizuku gives us on a non-rooted device.
     *
     * The package must additionally be carrier-privileged (see
     * `CarrierPrivilegesTracker.getCarrierService`), hence [applyCarrierPrivileges] first.
     */
    fun setCarrierServicePackageOverride(
        subId: Int,
        carrierServicePackage: String?,
        callingPackage: String
    ) {
        val (iface, proxy) = telephony()
        val s = String::class.java
        val m = method(
            iface, "setCarrierServicePackageOverride",
            Int::class.javaPrimitiveType!!, s, s
        )
        m.invoke(proxy, subId, carrierServicePackage, callingPackage)
    }

    // ------------------------------------------------------------- SIM facts

    /** The SIM's real MCC+MNC, e.g. "46000". Requires no runtime permission. */
    fun realMccMnc(context: Context, subId: Int): String? =
        telephonyManagerFor(context, subId)?.simOperator?.takeIf { it.isNotBlank() }

    /** The SIM's real service provider name. Requires no runtime permission. */
    fun realSpn(context: Context, subId: Int): String? =
        telephonyManagerFor(context, subId)?.simOperatorName?.takeIf { it.isNotBlank() }

    private fun telephonyManagerFor(context: Context, subId: Int): TelephonyManager? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching { tm.createForSubscriptionId(subId) }.getOrDefault(tm)
    }

    // ------------------------------------------------------ own certificates

    /**
     * SHA-256 of our own signing certificate(s), upper-case hex — the exact value the framework
     * compares against (`UiccAccessRule.getCertHash(signature, "SHA-256")`).
     */
    fun ownCertSha256(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val signatures: List<Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo
                when {
                    si == null -> emptyList()
                    si.hasMultipleSigners() -> si.apkContentsSigners?.toList().orEmpty()
                    else -> si.signingCertificateHistory?.toList().orEmpty()
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
            }
        signatures.map { CertHash.sha256Hex(it.toByteArray()) }
    }.onFailure { Log.e(TAG, "ownCertSha256 failed", it) }.getOrDefault(emptyList())
}
