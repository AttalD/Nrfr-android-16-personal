package com.github.nrfr.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PersistableBundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.PrivilegedTelephony
import java.util.Locale
import java.util.TimeZone

/**
 * 只读地采集本机的电话状态。
 *
 * Every read is individually guarded: a `SecurityException` from one property must not lose the
 * other nineteen. Nothing here mutates any state — this is the Phase A of the diagnostics, safe to
 * run at any time.
 */
object DiagnosticCollector {

    fun collect(context: Context, slot: Int, subId: Int): DiagnosticReport {
        val tm = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .let { runCatching { it.createForSubscriptionId(subId) }.getOrDefault(it) }

        val values = buildList {
            addAll(simValues(tm))
            addAll(networkValues(tm))
            addAll(frameworkValues(context, tm, subId))
            addAll(carrierConfigValues(context, subId))
            addAll(deviceValues())
        }

        val existing = runCatching { PrivilegedTelephony.carrierServicePackage(slot) }.getOrNull()

        return DiagnosticReport(
            subId = subId,
            slot = slot,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            values = values,
            existingCarrierServicePackage = existing
        )
    }

    /**
     * Every value, for the before/during/after comparison around the country-override experiment.
     *
     * Wider than [collectIdentity] on purpose: the experiment must also prove APN, data state and
     * network type were untouched, and those are framework values rather than identity ones.
     */
    fun collectAll(context: Context, subId: Int): List<DiagnosticValue> {
        val tm = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .let { runCatching { it.createForSubscriptionId(subId) }.getOrDefault(it) }
        return simValues(tm) +
                networkValues(tm) +
                frameworkValues(context, tm, subId) +
                carrierConfigValues(context, subId)
    }

    /** Only the identity values, used for the before/after comparison around a probe. */
    fun collectIdentity(context: Context, subId: Int): List<DiagnosticValue> {
        val tm = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .let { runCatching { it.createForSubscriptionId(subId) }.getOrDefault(it) }
        return simValues(tm) + networkValues(tm)
    }

    // ------------------------------------------------------------------ SIM

    private fun simValues(tm: TelephonyManager) = listOf(
        read("sim_operator", "SIM 运营商代码 (MCC+MNC)", ValueSource.SIM,
            Mutability.CHANGEABLE_ADVANCED, AppVisibility.NO_PERMISSION) { tm.simOperator },

        read("sim_operator_name", "SIM 运营商名称", ValueSource.SIM,
            Mutability.CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.simOperatorName },

        read("sim_country_iso", "SIM 国家码 (ISO)", ValueSource.SIM,
            Mutability.CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.simCountryIso },

        read("sim_carrier_id", "SIM Carrier ID", ValueSource.SIM,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.simCarrierId.toString() },

        read("sim_carrier_id_name", "SIM Carrier ID 名称", ValueSource.SIM,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.simCarrierIdName?.toString() },

        read("sim_specific_carrier_id", "SIM 精确 Carrier ID", ValueSource.SIM,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.simSpecificCarrierId.toString() },

        read("sim_state", "SIM 状态", ValueSource.SIM,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { simStateName(tm.simState) }
    )

    // -------------------------------------------------------------- network

    private fun networkValues(tm: TelephonyManager) = listOf(
        read("network_operator", "网络运营商代码 (MCC+MNC)", ValueSource.NETWORK,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.networkOperator },

        read("network_operator_name", "网络运营商名称", ValueSource.NETWORK,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.networkOperatorName },

        read("network_country_iso", "网络国家码 (ISO)", ValueSource.NETWORK,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.networkCountryIso },

        read("network_roaming", "是否漫游", ValueSource.NETWORK,
            Mutability.NOT_CHANGEABLE, AppVisibility.NO_PERMISSION) { tm.isNetworkRoaming.toString() },

        read("data_network_type", "当前网络制式", ValueSource.NETWORK,
            Mutability.NOT_CHANGEABLE, AppVisibility.NEEDS_PERMISSION) {
            networkTypeName(tm.dataNetworkType)
        }
    )

    // ------------------------------------------------------------ framework

    // isVoiceCapable/isSmsCapable/callState gained API-36 replacements, but minSdk here is 26 and
    // the deprecated forms are the ones that work across the whole supported range.
    @Suppress("DEPRECATION")
    private fun frameworkValues(context: Context, tm: TelephonyManager, subId: Int) = listOf(
        read("data_state", "数据连接状态", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { dataStateName(tm.dataState) },

        read("data_validated", "移动数据是否真正可用", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NEEDS_PERMISSION) { mobileDataWorking(context) },

        read("data_enabled", "移动数据开关", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NEEDS_PERMISSION) { tm.isDataEnabled.toString() },

        read("voice_capable", "支持通话", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { tm.isVoiceCapable.toString() },

        read("sms_capable", "支持短信", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { tm.isSmsCapable.toString() },

        read("call_state", "当前通话状态", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NEEDS_PERMISSION) { callStateName(tm.callState) },

        read("default_data_sub", "默认数据 subId", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) {
            SubscriptionManager.getDefaultDataSubscriptionId().toString()
        },

        read("default_sms_sub", "默认短信 subId", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) {
            SubscriptionManager.getDefaultSmsSubscriptionId().toString()
        },

        read("apn", "当前 APN", ValueSource.FRAMEWORK,
            Mutability.OUT_OF_SCOPE, AppVisibility.PRIVILEGED_ONLY) { readPreferredApn(context) }
    )

    // ------------------------------------------------------- carrier config

    private fun carrierConfigValues(context: Context, subId: Int): List<DiagnosticValue> {
        val bundle: PersistableBundle? = runCatching {
            PrivilegedTelephony.currentConfig(subId, context.packageName)
        }.getOrNull()

        fun cc(key: String, label: String, mutability: Mutability) = read(
            "cc_$key", label, ValueSource.CARRIER_CONFIG, mutability, AppVisibility.PRIVILEGED_ONLY
        ) {
            bundle ?: throw IllegalStateException("无法读取 CarrierConfig（Shizuku 未授权？）")
            @Suppress("DEPRECATION")
            bundle.get(key)?.toString() ?: "(未设置)"
        }

        return listOf(
            cc(CarrierConfigKeys.KEY_SIM_COUNTRY_ISO, "国家码覆盖项", Mutability.CHANGEABLE),
            cc(CarrierConfigKeys.KEY_CARRIER_NAME_OVERRIDE, "运营商名覆盖开关", Mutability.CHANGEABLE),
            cc(CarrierConfigKeys.KEY_CARRIER_NAME, "运营商名覆盖值", Mutability.CHANGEABLE),
            read("cc_size", "CarrierConfig 键总数", ValueSource.CARRIER_CONFIG,
                Mutability.OUT_OF_SCOPE, AppVisibility.PRIVILEGED_ONLY) {
                bundle?.keySet()?.size?.toString()
                    ?: throw IllegalStateException("无法读取 CarrierConfig（Shizuku 未授权？）")
            }
        )
    }

    // --------------------------------------------------------------- device

    private fun deviceValues() = listOf(
        read("locale", "系统语言", ValueSource.DEVICE,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { Locale.getDefault().toString() },

        read("timezone", "时区", ValueSource.DEVICE,
            Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION) { TimeZone.getDefault().id }
    )

    // --------------------------------------------------------------- helpers

    private inline fun read(
        key: String,
        label: String,
        source: ValueSource,
        mutability: Mutability,
        visibility: AppVisibility,
        block: () -> String?
    ): DiagnosticValue = try {
        DiagnosticValue(key, label, block(), source, mutability, visibility)
    } catch (t: Throwable) {
        DiagnosticValue(
            key, label, null, source, mutability, visibility,
            error = "${t.javaClass.simpleName}: ${t.message}"
        )
    }

    @Suppress("DEPRECATION")
    private fun mobileDataWorking(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cellular = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } ?: return "无蜂窝网络"
        val caps = cm.getNetworkCapabilities(cellular) ?: return "未知"
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return "internet=$internet validated=$validated"
    }

    /**
     * Reading the APN table needs carrier privileges or MODIFY_PHONE_STATE; an ordinary app gets a
     * SecurityException. We attempt it and report the failure rather than escalating — the task is
     * diagnostics, and APNs must not be touched.
     */
    private fun readPreferredApn(context: Context): String {
        val uri = android.net.Uri.parse("content://telephony/carriers/preferapn")
        context.contentResolver.query(uri, arrayOf("name", "apn"), null, null, null)
            .use { c ->
                if (c == null) return "不可读（无 provider 访问权限）"
                if (!c.moveToFirst()) return "(无记录)"
                return "${c.getString(0)} / ${c.getString(1)}"
            }
    }

    private fun simStateName(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        else -> "state=$state"
    }

    private fun dataStateName(state: Int) = when (state) {
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
        else -> "state=$state"
    }

    private fun callStateName(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> "state=$state"
    }

    private fun networkTypeName(type: Int) = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "type=$type"
    }
}
