package com.github.nrfr

import com.github.nrfr.manager.CertHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertHashTest {

    @Test
    fun `hex encoding is upper case and zero padded`() {
        assertEquals("00", CertHash.toHex(byteArrayOf(0)))
        assertEquals("0F", CertHash.toHex(byteArrayOf(0x0F)))
        assertEquals("FF", CertHash.toHex(byteArrayOf(0xFF.toByte())))
        assertEquals("DEADBEEF", CertHash.toHex(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        assertEquals("", CertHash.toHex(byteArrayOf()))
    }

    @Test
    fun `sha256 matches the digest the framework computes`() {
        // UiccAccessRule.getCertHash(signature, "SHA-256") == MessageDigest("SHA-256") over the
        // raw certificate bytes. Anchored on the well known digest of the empty input.
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            CertHash.sha256Hex(byteArrayOf())
        )
        assertEquals(64, CertHash.sha256Hex("nrfr".toByteArray()).length)
    }

    @Test
    fun `privilege rule stays bare hex`() {
        // Regression guard: the same string is fed to IccUtils.hexStringToBytes by
        // GsmCdmaPhone.setCarrierTestOverride, which throws on any non-hex character. Appending
        // ":<package>" — which the *other* consumer would accept — would blow up in the phone
        // process and could leave the override half applied.
        val hash = CertHash.sha256Hex("nrfr".toByteArray())
        val rule = CertHash.carrierPrivilegeRule(hash)
        assertEquals(hash, rule)
        assertTrue(rule.all { it in '0'..'9' || it in 'A'..'F' })
    }
}
