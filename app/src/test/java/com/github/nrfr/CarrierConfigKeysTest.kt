package com.github.nrfr

import com.github.nrfr.manager.CarrierConfigKeys
import com.github.nrfr.manager.OverrideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierConfigKeysTest {

    @Test
    fun `uses the platform carrier config key names`() {
        // These literals are what UiccProfile / ServiceStateTracker look for; getting them wrong
        // would silently produce a bundle the framework ignores.
        assertEquals("sim_country_iso_override_string", CarrierConfigKeys.KEY_SIM_COUNTRY_ISO)
        assertEquals("carrier_name_override_bool", CarrierConfigKeys.KEY_CARRIER_NAME_OVERRIDE)
        assertEquals("carrier_name_string", CarrierConfigKeys.KEY_CARRIER_NAME)
    }

    @Test
    fun `country only spec emits just the country key`() {
        val values = CarrierConfigKeys.forSpec(OverrideSpec.of("JP", null))
        assertEquals("jp", values[CarrierConfigKeys.KEY_SIM_COUNTRY_ISO])
        assertFalse(values.containsKey(CarrierConfigKeys.KEY_CARRIER_NAME_OVERRIDE))
    }

    @Test
    fun `carrier name requires the override flag to be set`() {
        // KEY_CARRIER_NAME_STRING is ignored by ServiceStateTracker unless the bool is true.
        val values = CarrierConfigKeys.forSpec(OverrideSpec.of(null, "NTT docomo"))
        assertEquals(true, values[CarrierConfigKeys.KEY_CARRIER_NAME_OVERRIDE])
        assertEquals("NTT docomo", values[CarrierConfigKeys.KEY_CARRIER_NAME])
    }

    @Test
    fun `empty spec produces no keys`() {
        assertTrue(CarrierConfigKeys.forSpec(OverrideSpec()).isEmpty())
    }

    @Test
    fun `mcc mnc is not a carrier config key`() {
        // It is applied via setCarrierTestOverride, not through the config bundle.
        val values = CarrierConfigKeys.forSpec(OverrideSpec.of("JP", null, "44010"))
        assertEquals(1, values.size)
    }

    @Test
    fun `round trips through toSpec`() {
        val original = OverrideSpec.of("us", "T-Mobile")
        val restored = CarrierConfigKeys.toSpec(CarrierConfigKeys.forSpec(original))
        assertEquals(original.countryIso, restored.countryIso)
        assertEquals(original.carrierName, restored.carrierName)
    }

    @Test
    fun `toSpec ignores a carrier name whose override flag is false`() {
        val restored = CarrierConfigKeys.toSpec(
            mapOf(
                CarrierConfigKeys.KEY_CARRIER_NAME_OVERRIDE to false,
                CarrierConfigKeys.KEY_CARRIER_NAME to "China Mobile"
            )
        )
        assertNull(restored.carrierName)
        assertTrue(restored.isEmpty)
    }

    @Test
    fun `toSpec tolerates a bundle with unrelated or wrongly typed entries`() {
        val restored = CarrierConfigKeys.toSpec(
            mapOf(
                CarrierConfigKeys.KEY_SIM_COUNTRY_ISO to 42,
                "some_other_key" to "value"
            )
        )
        assertNull(restored.countryIso)
    }
}
