package com.github.nrfr.region

/**
 * 每个信号在**本项目当前掌握的证据**下的能力评估。
 *
 * [evidence] is deliberately part of the record: a status of [SignalStatus.VERIFIED] must always
 * be traceable to something actually observed on hardware. Reading AOSP and concluding the OEM
 * behaves identically is [SignalStatus.EXPERIMENTAL], not verified.
 */
data class Capability(
    val signal: Signal,
    val status: SignalStatus,
    val evidence: String
) {
    val changeable: Boolean
        get() = status == SignalStatus.VERIFIED || status == SignalStatus.EXPERIMENTAL
}

object SignalCapabilities {

    /** 真机验证所在的设备/固件。 */
    const val VERIFIED_ON = "OnePlus 12R (CPH2609) / OxygenOS 16.0.5 / Android 16 (API 36)"

    private val table: Map<Signal, Capability> = listOf(
        Capability(
            Signal.SIM_COUNTRY_ISO, SignalStatus.VERIFIED,
            "真机实验：经 CarrierService 下发 KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING，" +
                    "getSimCountryIso() 确实 cn → us，且网络侧、MCC/MNC、Carrier ID、APN 均未变"
        ),
        Capability(
            Signal.SIM_OPERATOR_NAME, SignalStatus.EXPERIMENTAL,
            "机制与国家码相同（KEY_CARRIER_NAME_OVERRIDE_BOOL + KEY_CARRIER_NAME_STRING），" +
                    "但尚未单独在真机上核验 getSimOperatorName() 的变化"
        ),
        Capability(
            Signal.SIM_OPERATOR_NUMERIC, SignalStatus.EXPERIMENTAL,
            "CarrierConfig 中不存在 MCC/MNC 键，只能经 setCarrierTestOverride 写 " +
                    "gsm.sim.operator.numeric。源码上成立，真机未验证，且会连带改变 Carrier ID、" +
                    "可能影响 APN 匹配"
        ),
        Capability(
            Signal.SIM_CARRIER_ID, SignalStatus.READ_ONLY,
            "CarrierResolver 依 getSimOperatorNumericForPhone() 查 carrier-id 数据库推导，" +
                    "没有直接写入口；只会随 MCC/MNC 间接变化"
        ),
        Capability(
            Signal.NETWORK_COUNTRY_ISO, SignalStatus.UNSUPPORTED,
            "来自 ServiceState，由调制解调器按实际注册的基站上报；免 root 无受支持的写入口"
        ),
        Capability(
            Signal.NETWORK_OPERATOR_NUMERIC, SignalStatus.UNSUPPORTED,
            "同上，随基站决定"
        ),
        Capability(
            Signal.NETWORK_OPERATOR_NAME, SignalStatus.UNSUPPORTED,
            "同上，随基站决定"
        ),
        Capability(
            Signal.ROAMING, SignalStatus.UNSUPPORTED,
            "由 SIM 与网络的 MCC/MNC 比较得出，非独立可写值"
        ),
        Capability(
            Signal.CARRIER_CONFIG, SignalStatus.VERIFIED,
            "真机实验：注册为 CarrierService 后返回的 bundle 确实被合并进最终 CarrierConfig"
        ),
        Capability(
            Signal.APN_DATA, SignalStatus.READ_ONLY,
            "本项目**刻意不修改** APN；仅作为安全核验项观测，任何变化都视为副作用"
        ),
        Capability(
            Signal.LOCALE_TIMEZONE, SignalStatus.READ_ONLY,
            "由用户在系统设置中自行更改，不属于本应用职责"
        ),
        Capability(
            Signal.EXTERNAL_IP, SignalStatus.READ_ONLY,
            "取决于你自己的网络方案（VPN/代理），设备内无法改变"
        )
    ).associateBy { it.signal }

    operator fun get(signal: Signal): Capability =
        table[signal] ?: Capability(signal, SignalStatus.UNKNOWN, "未评估")

    fun all(): List<Capability> = Signal.entries.map { get(it) }

    /** 当前实现真正能改动的信号。 */
    fun changeable(): List<Capability> = all().filter { it.changeable }

    /** 免 root 无论如何都改不了的信号。 */
    fun impossible(): List<Capability> = all().filter { it.status == SignalStatus.UNSUPPORTED }
}
