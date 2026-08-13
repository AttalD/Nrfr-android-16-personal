package com.github.nrfr.manager

/**
 * 失败原因分类。Pure logic, unit tested on the JVM.
 *
 * The interesting one is [SHELL_BLOCKED]: since the Android 2025-10 security patch
 * (AOSP `CarrierConfigLoader.secureOverrideConfig`, bug 441823943 / CVE-2025-48617)
 * `ICarrierConfigLoader.overrideConfig` throws for the shell UID, which is exactly the UID
 * Shizuku hands us on a non-rooted device.
 */
enum class FailureKind {
    /** `overrideConfig cannot be invoked by shell` — the Android 16 hardening. */
    SHELL_BLOCKED,

    /** `overrideConfig with persistent=true only can be invoked by system app`. */
    PERSISTENT_REQUIRES_SYSTEM_APP,

    /** Key is on the user-build blocklist (satellite keys today). */
    BLOCKLISTED_KEY,

    /** The hidden API / AIDL method does not exist on this ROM. */
    MISSING_API,

    /** Shizuku is not running, or permission was not granted. */
    NO_PRIVILEGE,

    UNKNOWN;

    /** True when trying the modern CarrierService strategy instead is worthwhile. */
    val shouldFallBackToCarrierService: Boolean
        get() = this == SHELL_BLOCKED || this == PERSISTENT_REQUIRES_SYSTEM_APP
}

object TelephonyFailures {

    fun classify(t: Throwable?): FailureKind {
        var cause: Throwable? = t
        // Reflection wraps the real exception in InvocationTargetException.
        while (cause != null) {
            classifyMessage(cause.message)?.let { return it }
            if (cause is NoSuchMethodException || cause is ClassNotFoundException ||
                cause is NoSuchMethodError || cause is NoClassDefFoundError
            ) {
                return FailureKind.MISSING_API
            }
            cause = cause.cause
        }
        return FailureKind.UNKNOWN
    }

    private fun classifyMessage(message: String?): FailureKind? {
        val m = message?.lowercase() ?: return null
        return when {
            m.contains("cannot be invoked by shell") -> FailureKind.SHELL_BLOCKED
            m.contains("only can be invoked by system app") -> FailureKind.PERSISTENT_REQUIRES_SYSTEM_APP
            m.contains("is not allowed on user builds") -> FailureKind.BLOCKLISTED_KEY
            m.contains("shizuku") || m.contains("binder haven't been received") -> FailureKind.NO_PRIVILEGE
            else -> null
        }
    }

    /** User facing (Chinese, matching the rest of the app) explanation. */
    fun describe(kind: FailureKind): String = when (kind) {
        FailureKind.SHELL_BLOCKED ->
            "系统已阻止 shell 调用 overrideConfig（Android 2025-10 安全补丁 / CVE-2025-48617），已自动切换到 CarrierService 方案。"
        FailureKind.PERSISTENT_REQUIRES_SYSTEM_APP ->
            "系统禁止非系统应用写入持久化 carrier config，已自动切换到 CarrierService 方案。"
        FailureKind.BLOCKLISTED_KEY ->
            "该配置项在正式版系统上被禁止覆盖。"
        FailureKind.MISSING_API ->
            "当前 ROM 不提供所需的隐藏接口，无法在此设备上使用。"
        FailureKind.NO_PRIVILEGE ->
            "未获得 Shizuku 授权，请先启动 Shizuku 并授权。"
        FailureKind.UNKNOWN ->
            "未知错误。"
    }
}
