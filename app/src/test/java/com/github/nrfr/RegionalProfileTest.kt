package com.github.nrfr

import com.github.nrfr.region.*
import org.junit.Assert.*
import org.junit.Test

class RegionalProfileTest {

    // ------------------------------------------------------------- validation

    @Test
    fun `country only profile is valid and touches one signal`() {
        val p = RegionalProfile.countryOnly("us")
        assertTrue(p.validate().isEmpty())
        assertEquals(setOf(Signal.SIM_COUNTRY_ISO), p.touchedSignals())
        assertFalse("country override is verified, not experimental", p.usesExperimentalMechanism())
    }

    @Test
    fun `malformed values are rejected rather than sent to the framework`() {
        assertTrue(RegionalProfile("x", countryIso = "USA").validate().isNotEmpty())
        assertTrue(RegionalProfile("x", countryIso = "US").validate().isNotEmpty()) // must be lower
        assertTrue(RegionalProfile("x", operatorNumeric = "4401").validate().isNotEmpty())
        assertTrue(RegionalProfile("x", operatorNumeric = "4401000").validate().isNotEmpty())
        assertTrue(RegionalProfile("x", operatorNumeric = "44a10").validate().isNotEmpty())
        assertTrue(RegionalProfile("x", operatorName = "  ").validate().isNotEmpty())
    }

    @Test
    fun `mcc mnc profile also touches carrier id because it is derived`() {
        // CarrierResolver reads getSimOperatorNumericForPhone() to resolve the carrier id, so it
        // moves whether we want it to or not. Declaring it keeps it out of "unexpected side
        // effects" while still being visible to the user.
        val p = RegionalProfile("x", operatorNumeric = "310260")
        assertTrue(p.touchedSignals().contains(Signal.SIM_OPERATOR_NUMERIC))
        assertTrue(p.touchedSignals().contains(Signal.SIM_CARRIER_ID))
        assertTrue(p.usesExperimentalMechanism())
    }

    @Test
    fun `expected change keys drive the side-effect filter`() {
        val keys = RegionalProfile("x", countryIso = "us", operatorNumeric = "310260")
            .expectedChangeKeys()
        assertTrue(keys.contains("sim_country_iso"))
        assertTrue(keys.contains("sim_operator"))
        assertTrue(keys.contains("sim_carrier_id"))
        // Network signals must never be in here — we cannot change them at all.
        assertFalse(keys.contains("network_country_iso"))
        assertFalse(keys.contains("network_operator"))
    }

    @Test
    fun `empty profile is rejected`() {
        assertTrue(RegionalProfile("empty").isEmpty)
    }

    @Test
    fun `presets are all valid`() {
        RegionalProfile.PRESETS.forEach { p ->
            assertTrue("${p.name}: ${p.validate()}", p.validate().isEmpty())
            assertFalse(p.name, p.isEmpty)
        }
    }

    @Test
    fun `at least one preset avoids experimental mechanisms entirely`() {
        assertTrue(RegionalProfile.PRESETS.any { !it.usesExperimentalMechanism() })
    }

    // ------------------------------------------------------------ capabilities

    @Test
    fun `network signals are unsupported without root`() {
        listOf(
            Signal.NETWORK_COUNTRY_ISO, Signal.NETWORK_OPERATOR_NUMERIC,
            Signal.NETWORK_OPERATOR_NAME, Signal.ROAMING
        ).forEach {
            assertEquals(it.name, SignalStatus.UNSUPPORTED, SignalCapabilities[it].status)
            assertFalse(it.name, SignalCapabilities[it].changeable)
            assertEquals(it.name, Mechanism.NONE, it.mechanism)
        }
    }

    @Test
    fun `sim country is the one verified override`() {
        assertEquals(SignalStatus.VERIFIED, SignalCapabilities[Signal.SIM_COUNTRY_ISO].status)
        assertEquals(Mechanism.CARRIER_CONFIG, Signal.SIM_COUNTRY_ISO.mechanism)
    }

    @Test
    fun `mcc mnc does NOT go through carrier config`() {
        // There is no CarrierConfig key for the SIM operator numeric — only SIM_COUNTRY_ISO,
        // CARRIER_NAME, SPDI, EHPLMN, PNN and OPL have *_OVERRIDE_* keys. Getting this wrong would
        // mean silently shipping a mechanism that cannot work.
        assertEquals(Mechanism.CARRIER_TEST_OVERRIDE, Signal.SIM_OPERATOR_NUMERIC.mechanism)
        assertEquals(SignalStatus.EXPERIMENTAL, SignalCapabilities[Signal.SIM_OPERATOR_NUMERIC].status)
    }

    @Test
    fun `carrier id is read only and derived`() {
        assertEquals(Provenance.DERIVED, Signal.SIM_CARRIER_ID.provenance)
        assertEquals(SignalStatus.READ_ONLY, SignalCapabilities[Signal.SIM_CARRIER_ID].status)
    }

    @Test
    fun `every signal has a capability with evidence`() {
        Signal.entries.forEach {
            val c = SignalCapabilities[it]
            assertNotEquals(it.name, SignalStatus.UNKNOWN, c.status)
            assertTrue(it.name, c.evidence.isNotBlank())
        }
    }

    @Test
    fun `identity set matches the eight fields we verify`() {
        assertEquals(
            setOf(
                "sim_country_iso", "sim_operator_name", "sim_operator", "sim_carrier_id",
                "network_country_iso", "network_operator", "network_operator_name",
                "network_roaming"
            ),
            Signal.IDENTITY_KEYS
        )
    }
}
