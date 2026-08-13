package com.github.nrfr.manager

import java.security.MessageDigest

/**
 * 证书哈希工具。
 *
 * The framework matches carrier privilege rules by comparing the **SHA-256 of
 * `Signature.toByteArray()`** (see `UiccAccessRule.getCertHash`, and
 * `CarrierPrivilegesTracker.getCertsForPackage` which feeds it). The value we hand to
 * `ITelephony.setCarrierTestOverride(..., carrierPrivilegeRules, ...)` must therefore be the
 * upper-case hex encoding of that digest — `UiccAccessRule.decodeRulesFromCarrierConfig` parses it
 * with `IccUtils.hexStringToBytes`, optionally followed by `":<packageName>"`.
 *
 * Pure logic — unit tested on the JVM.
 */
object CertHash {

    private const val HEX = "0123456789ABCDEF"

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun sha256Hex(der: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(der))

    /**
     * Builds the `carrierPrivilegeRules` string passed to `ITelephony.setCarrierTestOverride`.
     *
     * **It must be a bare hex hash with no `":<package>"` suffix.** That single argument is parsed
     * by two different code paths and only one of them understands the suffix:
     *  - `CarrierPrivilegesTracker.handleSetTestOverrideRules` uses
     *    `UiccAccessRule.decodeRulesFromCarrierConfig`, which *does* split on ":" ;
     *  - `GsmCdmaPhone.setCarrierTestOverride` feeds the very same string straight into
     *    `IccUtils.hexStringToBytes`, which throws on any non-hex character.
     *
     * So a suffixed value would blow up inside the phone process and could leave the override
     * half-applied. Bare hex matches any package carrying this signing certificate, which is only
     * ever our own APK, and is undone again by [PrivilegedTelephony.clearCarrierPrivileges].
     */
    fun carrierPrivilegeRule(sha256Hex: String): String = sha256Hex
}
