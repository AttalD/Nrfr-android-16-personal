package com.github.nrfr

import com.github.nrfr.diag.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryOverrideResultTest {

    private val ok = listOf(ProbeStep("register", true))

    private fun cmp(
        key: String,
        before: String?,
        after: String?,
        outcome: ComparisonOutcome,
        identity: Boolean = true
    ) = ValueComparison(key, key, outcome, before, after, identity)

    private fun unchanged(key: String, v: String) =
        cmp(key, v, v, ComparisonOutcome.UNCHANGED)

    /** The ideal outcome: only sim_country_iso moved, everything else held. */
    private fun healthyDuring() = listOf(
        cmp("sim_country_iso", "cn", "us", ComparisonOutcome.CHANGED),
        unchanged("network_country_iso", "cn"),
        unchanged("sim_operator", "46000"),
        unchanged("network_operator", "46000"),
        unchanged("sim_carrier_id", "1435"),
        unchanged("network_roaming", "false"),
        cmp("apn", "CMNET / cmnet", "CMNET / cmnet", ComparisonOutcome.UNCHANGED, identity = false)
    )

    private fun result(
        configKeyPresent: Boolean = true,
        configKeyValue: String? = "us",
        before: String? = "cn",
        during: String? = "us",
        after: String? = "cn",
        duringCmp: List<ValueComparison> = healthyDuring(),
        afterCmp: List<ValueComparison> = listOf(unchanged("sim_country_iso", "cn"))
    ) = CountryOverrideResult(
        requestedCountry = "us",
        steps = ok,
        configKeyPresent = configKeyPresent,
        configKeyValue = configKeyValue,
        simCountryBefore = before,
        simCountryDuring = during,
        simCountryAfter = after,
        comparisonsDuring = duringCmp,
        comparisonsAfter = afterCmp
    )

    // ------------------------------------------------------------- verdicts

    @Test
    fun `A and B both succeeding is EFFECTIVE`() {
        val r = result()
        assertTrue(r.overrideAccepted)
        assertTrue(r.simCountryChanged)
        assertEquals(OverrideVerdict.EFFECTIVE, r.verdict)
        assertTrue(r.cleanSuccess)
    }

    @Test
    fun `config accepted but country unmoved is reported distinctly, not as rejection`() {
        // The case we specifically need to detect: an OEM build merges the key but never runs
        // UiccProfile.handleSimCountryIsoOverride(). That is NOT the same as the key being
        // rejected, and it must not be silently retried with another mechanism.
        val r = result(
            during = "cn",
            duringCmp = listOf(unchanged("sim_country_iso", "cn"), unchanged("sim_operator", "46000"))
        )
        assertTrue("key did reach the config", r.overrideAccepted)
        assertFalse(r.simCountryChanged)
        assertEquals(OverrideVerdict.CONFIG_ACCEPTED_NO_EFFECT, r.verdict)
        assertFalse(r.cleanSuccess)
    }

    @Test
    fun `key never reaching the config is CONFIG_REJECTED`() {
        val r = result(configKeyPresent = false, configKeyValue = null, during = "cn")
        assertEquals(OverrideVerdict.CONFIG_REJECTED, r.verdict)
    }

    @Test
    fun `no steps means the experiment did not run`() {
        assertEquals(
            OverrideVerdict.NOT_RUN,
            CountryOverrideResult("us", emptyList()).verdict
        )
    }

    @Test
    fun `country changing to something other than requested is not EFFECTIVE`() {
        // e.g. the ROM normalises it to the network's country instead of honouring ours.
        val r = result(during = "jp")
        assertTrue(r.simCountryChanged)
        assertFalse(r.simCountryMatchesRequest)
        assertEquals(OverrideVerdict.CONFIG_ACCEPTED_NO_EFFECT, r.verdict)
    }

    @Test
    fun `country comparison is case insensitive`() {
        assertTrue(result(during = "US").simCountryMatchesRequest)
        assertFalse(result(before = "CN", during = "cn").simCountryChanged)
    }

    @Test
    fun `an unreadable country is never treated as a change`() {
        assertFalse(result(during = null).simCountryChanged)
        assertFalse(result(before = null).simCountryChanged)
    }

    // ------------------------------------------------- side effects (C and D)

    @Test
    fun `C and D hold in the healthy case`() {
        val r = result()
        assertTrue("network country held", r.networkCountryHeld)
        assertTrue("SIM MCC-MNC held", r.simOperatorHeld)
        assertTrue(r.networkOperatorHeld)
        assertTrue(r.carrierIdHeld)
        assertTrue(r.apnHeld)
        assertTrue(r.roamingHeld)
        assertTrue(r.unexpectedSideEffects.isEmpty())
    }

    @Test
    fun `a changed SIM MCC-MNC is flagged as an unexpected side effect`() {
        // This must never happen in the country-only experiment; if it does, the run is unsafe.
        val r = result(
            duringCmp = healthyDuring().map {
                if (it.key == "sim_operator") cmp("sim_operator", "46000", "310260", ComparisonOutcome.CHANGED)
                else it
            }
        )
        assertFalse(r.simOperatorHeld)
        assertEquals(listOf("sim_operator"), r.unexpectedSideEffects.map { it.key })
        assertFalse(r.cleanSuccess)
    }

    @Test
    fun `a changed network country is flagged`() {
        val r = result(
            duringCmp = healthyDuring().map {
                if (it.key == "network_country_iso")
                    cmp("network_country_iso", "cn", "us", ComparisonOutcome.CHANGED)
                else it
            }
        )
        assertFalse(r.networkCountryHeld)
        assertFalse(r.cleanSuccess)
    }

    @Test
    fun `the intended sim_country_iso change is not counted as a side effect`() {
        assertTrue(result().unexpectedSideEffects.isEmpty())
        assertEquals(setOf("sim_country_iso"), EXPECTED_CHANGE_KEYS)
    }

    @Test
    fun `a readability artifact during the experiment is not a side effect`() {
        // Same class of false positive fixed for the probe: carrier privileges make some fields
        // readable, which is not a mutation.
        val r = result(
            duringCmp = healthyDuring() + cmp(
                "data_network_type", null, "5G NR",
                ComparisonOutcome.BECAME_READABLE, identity = false
            )
        )
        assertTrue(r.unexpectedSideEffects.isEmpty())
        assertTrue(r.cleanSuccess)
    }

    // ------------------------------------------------------------- revert

    @Test
    fun `revert is clean when nothing differs from baseline`() {
        assertTrue(result().revertRestored)
        assertTrue(result().revertFailures.isEmpty())
    }

    @Test
    fun `a country stuck at us after revert is a revert failure`() {
        val r = result(
            after = "us",
            afterCmp = listOf(cmp("sim_country_iso", "cn", "us", ComparisonOutcome.CHANGED))
        )
        assertFalse(r.revertRestored)
        assertEquals(listOf("sim_country_iso"), r.revertFailures.map { it.key })
        assertFalse(r.cleanSuccess)
    }

    @Test
    fun `an effective override with a dirty revert is still reported as effective`() {
        // The verdict describes the mechanism; revert hygiene is tracked separately.
        val r = result(
            after = "us",
            afterCmp = listOf(cmp("sim_country_iso", "cn", "us", ComparisonOutcome.CHANGED))
        )
        assertEquals(OverrideVerdict.EFFECTIVE, r.verdict)
        assertFalse(r.cleanSuccess)
    }
}
