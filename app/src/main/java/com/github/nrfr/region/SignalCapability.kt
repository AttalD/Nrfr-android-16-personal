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
            "真机 run #12/#13/#14：经 CarrierService 下发 KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING，" +
                    "getSimCountryIso() 确实 cn → us → cn，还原经回读校验；" +
                    "仅改国家码时网络侧、MCC/MNC、Carrier ID、APN 均未变"
        ),
        Capability(
            Signal.SIM_OPERATOR_NAME, SignalStatus.VERIFIED,
            "真机 run #13/#14：CMCC → T-Mobile 回读生效，还原后回到 CMCC"
        ),
        Capability(
            Signal.SIM_OPERATOR_NUMERIC, SignalStatus.VERIFIED,
            "真机 run #13/#14：46000 → 310260 → 46000 回读生效并完整还原。" +
                    "CarrierConfig 中不存在 MCC/MNC 键，机制为 setCarrierTestOverride 写 " +
                    "gsm.sim.operator.numeric。已知连带影响：Carrier ID 随之改变（预期内）、" +
                    "APN 按运营商代码重新匹配；实测移动数据保持正常，网络侧 MCC/MNC 与国家码未变"
        ),
        Capability(
            Signal.SIM_CARRIER_ID, SignalStatus.READ_ONLY,
            "CarrierResolver 依 getSimOperatorNumericForPhone() 查 carrier-id 数据库推导，" +
                    "没有直接写入口。真机 run #13/#14 证实其随 MCC/MNC 间接变化：" +
                    "1435 → 1 → 1435，属预期而非副作用"
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
            "真机实验：注册为 CarrierService 后返回的 bundle 确实被合并进最终 CarrierConfig；" +
                    "run #14 另证实还原后国家码键回到原始的「不存在」状态"
        ),
        Capability(
            Signal.APN_DATA, SignalStatus.READ_ONLY,
            "本项目**刻意不修改** APN；仅作为安全核验项观测。改动 MCC/MNC 时框架会按运营商代码" +
                    "重新匹配 APN，此为预期；真机 run #13/#14 下移动数据始终正常，还原后 APN 回到原值"
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
