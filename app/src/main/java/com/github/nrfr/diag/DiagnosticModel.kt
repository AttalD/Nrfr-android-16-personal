package com.github.nrfr.diag

/**
 * 诊断值的来源。这是整份报告的核心：同一个"国家"概念在 Android 里有好几个互不相同的来源，
 * 而我们只能影响其中一部分。
 */
enum class ValueSource(val label: String, val blurb: String) {
    /** 来自物理 SIM 卡（EF 文件 / IMSI），经 CarrierConfig 覆盖后可变。 */
    SIM("SIM 卡", "读自物理 SIM，可被 CarrierConfig 覆盖"),

    /** 来自当前注册的基站/网络，取决于你人在哪、连的哪个塔。 */
    NETWORK("蜂窝网络", "读自当前注册的基站，随所在地变化"),

    /** 由 CarrierConfig 体系产生 —— 这正是本应用的作用点。 */
    CARRIER_CONFIG("CarrierConfig", "运营商配置，本应用的作用点"),

    /** 框架状态（连接性、能力位等），不属于身份标识。 */
    FRAMEWORK("框架状态", "系统运行状态，非身份标识"),

    /** 设备/用户设置，与电话网络无关。 */
    DEVICE("设备设置", "语言、时区等，与 SIM 无关");
}

/**
 * 本应用在**不 root、不改系统分区**的前提下，对某个值的改动能力。
 */
enum class Mutability(val label: String) {
    /** 当前实现已能改（CarrierService 方案，默认路径）。 */
    CHANGEABLE("✅ 可修改（当前方案）"),

    /** 需要启用进阶的 MCC/MNC 伪装，有影响移动数据的风险。 */
    CHANGEABLE_ADVANCED("⚠️ 需进阶选项（有风险）"),

    /** 免 root 无法修改：值由基站或调制解调器决定。 */
    NOT_CHANGEABLE("❌ 免 root 无法修改"),

    /** 与本应用无关（由系统设置或网络环境决定）。 */
    OUT_OF_SCOPE("— 不在本应用范围");
}

/** 普通第三方应用（如 TikTok）能否读到这个值。 */
enum class AppVisibility(val label: String) {
    /** 无需任何权限即可读取 —— 风控最常用的一类。 */
    NO_PERMISSION("无需权限"),

    /** 需要 READ_PHONE_STATE 等运行时权限。 */
    NEEDS_PERMISSION("需运行时权限"),

    /** 普通应用读不到（需特权权限）。 */
    PRIVILEGED_ONLY("普通应用不可见");
}

data class DiagnosticValue(
    val key: String,
    val label: String,
    val value: String?,
    val source: ValueSource,
    val mutability: Mutability,
    val visibility: AppVisibility,
    /** 读取失败时的原因（权限不足 / API 缺失等）。 */
    val error: String? = null
) {
    val display: String get() = error?.let { "读取失败: $it" } ?: value?.takeIf { it.isNotBlank() } ?: "(空)"
}

/** 探测 CarrierService 机制时，每一步的结果。 */
data class ProbeStep(
    val name: String,
    val ok: Boolean,
    val detail: String? = null
)

data class ProbeResult(
    val steps: List<ProbeStep>,
    /** 框架是否真的回调了我们的 onLoadConfig。 */
    val onLoadConfigInvoked: Boolean,
    /** 我们返回的哨兵键是否出现在最终合并后的 CarrierConfig 中。 */
    val configApplied: Boolean,
    /** 探测前后所有身份值是否完全一致。 */
    val identityUnchanged: Boolean,
    val changedValues: List<String> = emptyList()
) {
    val succeeded: Boolean get() = steps.all { it.ok } && onLoadConfigInvoked && configApplied
}

data class DiagnosticReport(
    val subId: Int,
    val slot: Int,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val values: List<DiagnosticValue>,
    val existingCarrierServicePackage: String?,
    val probe: ProbeResult? = null
) {
    fun bySource(source: ValueSource): List<DiagnosticValue> = values.filter { it.source == source }

    /**
     * 探测前必须满足的安全前提：当前没有别的应用被绑定为 CarrierService。
     *
     * 若已有其它 carrier 应用被绑定，把自己顶上去会**顶掉它提供的配置**（可能包含 VoLTE/IMS 等
     * 关键项），这已经超出"诊断"的范畴。
     */
    val probeIsSafe: Boolean get() = existingCarrierServicePackage.isNullOrBlank()
}
