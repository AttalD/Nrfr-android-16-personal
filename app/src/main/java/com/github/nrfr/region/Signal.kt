package com.github.nrfr.region

/**
 * 一个"地区信号"——应用可以据以判断设备所属地区的一个具体值。
 *
 * The central idea of this package: these are **independent**. They come from different places,
 * are written by different framework code, and have wildly different mutability. Treating
 * "the device's region" as one value is exactly the mistake that makes this problem confusing.
 */
enum class Signal(
    val key: String,
    val label: String,
    /** 值的真正来源。 */
    val provenance: Provenance,
    /** 我们能用什么机制去改它（若能）。 */
    val mechanism: Mechanism,
    /** 普通第三方应用能否读到。 */
    val appReadable: AppReadable
) {
    SIM_COUNTRY_ISO(
        "sim_country_iso", "SIM 国家码 (ISO)",
        Provenance.SIM, Mechanism.CARRIER_CONFIG, AppReadable.NO_PERMISSION
    ),
    SIM_OPERATOR_NAME(
        "sim_operator_name", "SIM 运营商名称",
        Provenance.SIM, Mechanism.CARRIER_CONFIG, AppReadable.NO_PERMISSION
    ),
    SIM_OPERATOR_NUMERIC(
        "sim_operator", "SIM 运营商代码 (MCC+MNC)",
        Provenance.SIM, Mechanism.CARRIER_TEST_OVERRIDE, AppReadable.NO_PERMISSION
    ),
    SIM_CARRIER_ID(
        "sim_carrier_id", "SIM Carrier ID",
        Provenance.DERIVED, Mechanism.DERIVED_FROM_OPERATOR_NUMERIC, AppReadable.NO_PERMISSION
    ),
    NETWORK_COUNTRY_ISO(
        "network_country_iso", "网络国家码 (ISO)",
        Provenance.NETWORK, Mechanism.NONE, AppReadable.NO_PERMISSION
    ),
    NETWORK_OPERATOR_NUMERIC(
        "network_operator", "网络运营商代码 (MCC+MNC)",
        Provenance.NETWORK, Mechanism.NONE, AppReadable.NO_PERMISSION
    ),
    NETWORK_OPERATOR_NAME(
        "network_operator_name", "网络运营商名称",
        Provenance.NETWORK, Mechanism.NONE, AppReadable.NO_PERMISSION
    ),
    ROAMING(
        "network_roaming", "漫游状态",
        Provenance.NETWORK, Mechanism.NONE, AppReadable.NO_PERMISSION
    ),
    CARRIER_CONFIG(
        "cc_size", "CarrierConfig",
        Provenance.CARRIER_CONFIG, Mechanism.CARRIER_CONFIG, AppReadable.PRIVILEGED_ONLY
    ),
    APN_DATA(
        "apn", "APN / 数据状态",
        Provenance.FRAMEWORK, Mechanism.NONE, AppReadable.PRIVILEGED_ONLY
    ),
    LOCALE_TIMEZONE(
        "locale", "语言 / 时区",
        Provenance.DEVICE, Mechanism.USER_SETTING, AppReadable.NO_PERMISSION
    ),
    EXTERNAL_IP(
        "external_ip", "出口 IP / VPN 地区",
        Provenance.EXTERNAL, Mechanism.EXTERNAL_TOOLING, AppReadable.NO_PERMISSION
    );

    companion object {
        fun byKey(key: String): Signal? = entries.firstOrNull { it.key == key }

        /** 参与"身份是否被改动"判定的信号。 */
        val IDENTITY: Set<Signal> = setOf(
            SIM_COUNTRY_ISO, SIM_OPERATOR_NAME, SIM_OPERATOR_NUMERIC, SIM_CARRIER_ID,
            NETWORK_COUNTRY_ISO, NETWORK_OPERATOR_NUMERIC, NETWORK_OPERATOR_NAME, ROAMING
        )

        val IDENTITY_KEYS: Set<String> = IDENTITY.map { it.key }.toSet()
    }
}

enum class Provenance(val label: String) {
    SIM("物理 SIM（EF/IMSI）"),
    NETWORK("当前注册的基站"),
    CARRIER_CONFIG("CarrierConfig 体系"),
    DERIVED("由其它信号推导"),
    FRAMEWORK("框架运行状态"),
    DEVICE("设备/用户设置"),
    EXTERNAL("设备之外")
}

/** 我们能用来改动某个信号的机制。 */
enum class Mechanism(val label: String) {
    /**
     * 经 CarrierService 下发 CarrierConfig 键。
     * 真机已验证：`KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING` 确实改变 `getSimCountryIso()`。
     */
    CARRIER_CONFIG("CarrierService / CarrierConfig"),

    /**
     * `ITelephony.setCarrierTestOverride(...)` 直接写
     * `gsm.sim.operator.numeric` / `.alpha`。
     *
     * **CarrierConfig 里根本不存在 MCC/MNC 的键**（只有 SIM_COUNTRY_ISO / CARRIER_NAME /
     * SPDI / EHPLMN / PNN / OPL 这几个 `*_OVERRIDE_*`），所以 CarrierService 这条路
     * 对 MCC/MNC 完全无效，只能走这里。
     */
    CARRIER_TEST_OVERRIDE("setCarrierTestOverride"),

    /** 由别的信号推导得到，只能间接影响。 */
    DERIVED_FROM_OPERATOR_NUMERIC("随 MCC/MNC 间接变化"),

    /** 免 root 无法改动。 */
    NONE("免 root 无法改动"),

    /** 用户在系统设置里自己改。 */
    USER_SETTING("系统设置"),

    /** 需要设备之外的手段（VPN/代理）。 */
    EXTERNAL_TOOLING("外部网络方案")
}

enum class AppReadable(val label: String) {
    NO_PERMISSION("无需权限"),
    NEEDS_PERMISSION("需运行时权限"),
    PRIVILEGED_ONLY("普通应用不可见")
}

/**
 * 某个信号在**本机**上的支持状态。
 *
 * The distinction that keeps this project honest: [VERIFIED] may only be set by an actual
 * on-device observation, never by reading AOSP source and assuming the OEM behaves the same.
 */
enum class SignalStatus(val label: String) {
    /** 未被改动，报告真实值。 */
    REAL("真实值"),

    /** 已被本应用改动，且当前生效中。 */
    OVERRIDDEN("已覆盖（生效中）"),

    /** 机制已在**真机**上验证可用。 */
    VERIFIED("✅ 真机已验证"),

    /** 机制理论可行但尚未在真机验证。 */
    EXPERIMENTAL("⚠️ 实验性（未验证）"),

    /** 免 root 不可能改动。 */
    UNSUPPORTED("❌ 免 root 不可改"),

    /** 只读：可观测但不可改。 */
    READ_ONLY("只读"),

    UNKNOWN("未知")
}
