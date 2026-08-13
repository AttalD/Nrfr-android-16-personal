package com.github.nrfr

import com.github.nrfr.manager.OverrideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverrideSpecTest {

    @Test
    fun `country code is normalised to lower case`() {
        assertEquals("jp", OverrideSpec.of("JP", null).countryIso)
        assertEquals("us", OverrideSpec.of("us", null).countryIso)
        assertEquals("jp", OverrideSpec.of("  Jp  ", null).countryIso)
    }

    @Test
    fun `malformed country codes are rejected rather than passed through`() {
        // A bad ISO would be written straight into gsm.sim.operator.iso-country, so it must not
        // reach the framework at all.
        assertNull(OverrideSpec.of("J", null).countryIso)
        assertNull(OverrideSpec.of("JPN", null).countryIso)
        assertNull(OverrideSpec.of("4A", null).countryIso)
        assertNull(OverrideSpec.of("", null).countryIso)
        assertNull(OverrideSpec.of(null, null).countryIso)
    }

    @Test
    fun `carrier name is trimmed and blanks become null`() {
        assertEquals("NTT docomo", OverrideSpec.of(null, "  NTT docomo  ").carrierName)
        assertNull(OverrideSpec.of(null, "   ").carrierName)
        assertNull(OverrideSpec.of(null, "").carrierName)
    }

    @Test
    fun `mcc mnc must be five or six digits`() {
        assertEquals("44010", OverrideSpec.of(null, null, "44010").simOperatorNumeric)
        assertEquals("310260", OverrideSpec.of(null, null, "310260").simOperatorNumeric)
        assertNull(OverrideSpec.of(null, null, "4401").simOperatorNumeric)
        assertNull(OverrideSpec.of(null, null, "4401000").simOperatorNumeric)
        assertNull(OverrideSpec.of(null, null, "4401a").simOperatorNumeric)
    }

    @Test
    fun `isEmpty reflects that nothing would be overridden`() {
        assertTrue(OverrideSpec.of(null, null).isEmpty)
        assertTrue(OverrideSpec.of("bad", "  ").isEmpty)
        assertTrue(!OverrideSpec.of("JP", null).isEmpty)
    }

    @Test
    fun `describe renders only the fields that are set`() {
        val d = OverrideSpec.of("jp", "NTT docomo").describe()
        assertEquals("JP", d[OverrideSpec.KEY_COUNTRY])
        assertEquals("NTT docomo", d[OverrideSpec.KEY_CARRIER])
        assertNull(d[OverrideSpec.KEY_MCCMNC])
    }
}
