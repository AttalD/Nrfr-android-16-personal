package com.github.nrfr

import com.github.nrfr.diag.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierServiceReleaseTest {

    private val ours = "com.github.nrfr"

    @Test
    fun `no binding means released`() {
        assertTrue(CarrierServiceRelease.isReleased(null, ours))
        assertTrue(CarrierServiceRelease.isReleased("", ours))
        assertTrue(CarrierServiceRelease.isReleased("   ", ours))
    }

    @Test
    fun `still bound to us is not released`() {
        // The run #9 symptom.
        assertFalse(CarrierServiceRelease.isReleased(ours, ours))
    }

    @Test
    fun `bound to someone else counts as released for our purposes`() {
        // We only ever undo our own binding; a real carrier app being bound is the normal state.
        assertTrue(CarrierServiceRelease.isReleased("com.oem.carrier", ours))
    }

    // --------------------------------------------------- experiment integration

    private fun cmp(key: String, before: String?, after: String?) = ValueComparison(
        key, key,
        if (before == after) ComparisonOutcome.UNCHANGED else ComparisonOutcome.CHANGED,
        before, after, isIdentity = true
    )

    private fun result(released: Boolean, bound: String?) = CountryOverrideResult(
        requestedCountry = "us",
        steps = listOf(ProbeStep("register", true)),
        configKeyPresent = true,
        configKeyValue = "us",
        simCountryBefore = "cn",
        simCountryDuring = "us",
        simCountryAfter = "cn",
        originalConfigKey = null,
        postCleanupConfigKey = null,
        restoreOutcome = RestoreOutcome.RESTORED,
        carrierServiceReleased = released,
        boundPackageAfter = bound,
        comparisonsDuring = listOf(cmp("sim_country_iso", "cn", "us")),
        comparisonsAfter = listOf(cmp("sim_country_iso", "cn", "cn"))
    )

    @Test
    fun `a leftover binding makes the run not fully restored`() {
        // Exactly run #9: country restored perfectly, but we stayed bound, which blocks the next
        // probe. That is not a clean run.
        val r = result(released = false, bound = ours)
        assertTrue("country did come back", r.simCountryRestored)
        assertTrue(r.configKeyRestored)
        assertTrue(r.revertRestored)
        assertFalse("but the binding leaked", r.fullyRestored)
        assertFalse(r.cleanSuccess)
    }

    @Test
    fun `releasing the binding completes the cleanup`() {
        val r = result(released = true, bound = null)
        assertTrue(r.fullyRestored)
        assertTrue(r.cleanSuccess)
    }

    @Test
    fun `report states the post-cleanup binding`() {
        val text = ReportFormatter.formatCountryExperiment(result(released = false, bound = ours))
        assertTrue(text.contains("CarrierService"))
        assertTrue(text.contains(ours))
    }
}
