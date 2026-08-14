package com.github.nrfr

import com.github.nrfr.diag.ComparisonOutcome
import com.github.nrfr.diag.ValueComparison
import com.github.nrfr.region.ApnDataSafety
import com.github.nrfr.region.DataHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the run #11 APN/data false positive.
 *
 * The transaction reported "APN / 数据状态发生变化" on a device whose mobile data was fine, because
 * it snapshot-diffed `apn`, `data_state` and `data_validated` — all three of which move for
 * reasons that are not damage.
 */
class ApnDataSafetyTest {

    private fun cmp(key: String, before: String?, after: String?, outcome: ComparisonOutcome) =
        ValueComparison(key, key, outcome, before, after, isIdentity = false)

    // ------------------------------------------------------------------- APN

    @Test
    fun `apn becoming unreadable is not a change`() {
        // Exactly run #11: readable while we held carrier privileges, SecurityException after they
        // were dropped. That is a loss of observation, not a modified APN.
        assertFalse(
            ApnDataSafety.apnChanged(
                listOf(cmp("apn", "CMNET / cmnet", null, ComparisonOutcome.BECAME_UNREADABLE))
            )
        )
    }

    @Test
    fun `apn unreadable both times is not a change`() {
        assertFalse(
            ApnDataSafety.apnChanged(
                listOf(cmp("apn", null, null, ComparisonOutcome.NOT_COMPARABLE))
            )
        )
    }

    @Test
    fun `a genuinely different apn is a change`() {
        assertTrue(
            ApnDataSafety.apnChanged(
                listOf(cmp("apn", "CMNET / cmnet", "internet / fast.t-mobile.com", ComparisonOutcome.CHANGED))
            )
        )
    }

    // ------------------------------------------------------------ data health

    @Test
    fun `validated cellular is healthy`() {
        assertEquals(
            DataHealth.HEALTHY,
            ApnDataSafety.healthFrom("CONNECTED", "internet=true validated=true")
        )
    }

    @Test
    fun `the run 11 sample is healthy despite internet=false`() {
        // The real device reported `internet=false validated=true` while data worked fine.
        // `validated` means the system probed and reached the internet, so this is healthy.
        assertEquals(
            DataHealth.HEALTHY,
            ApnDataSafety.healthFrom("CONNECTED", "internet=false validated=true")
        )
    }

    @Test
    fun `connected alone is enough to be healthy`() {
        assertEquals(DataHealth.HEALTHY, ApnDataSafety.healthFrom("CONNECTED", null))
    }

    @Test
    fun `no cellular network at all is degraded`() {
        assertEquals(
            DataHealth.DEGRADED,
            ApnDataSafety.healthFrom("DISCONNECTED", "无蜂窝网络")
        )
    }

    @Test
    fun `nothing readable is unknown, not degraded`() {
        assertEquals(DataHealth.UNKNOWN, ApnDataSafety.healthFrom(null, null))
    }

    // ------------------------------------------------------------- the verdict

    @Test
    fun `a transient dip that recovered is not damage`() {
        // awaitHealthy polls until it settles; a CONNECTED -> DISCONNECTED -> CONNECTED cycle
        // therefore ends at HEALTHY and must not be reported as damage.
        assertFalse(ApnDataSafety.isDamaged(false, DataHealth.HEALTHY, DataHealth.HEALTHY))
    }

    @Test
    fun `healthy before and degraded after IS damage`() {
        // The check must stay conservative: real connectivity loss is still caught.
        assertTrue(ApnDataSafety.isDamaged(false, DataHealth.HEALTHY, DataHealth.DEGRADED))
    }

    @Test
    fun `a changed apn is damage regardless of connectivity`() {
        assertTrue(ApnDataSafety.isDamaged(true, DataHealth.HEALTHY, DataHealth.HEALTHY))
    }

    @Test
    fun `we do not blame the transaction for pre-existing trouble`() {
        // Data was already down before we started, e.g. no signal in a lift.
        assertFalse(ApnDataSafety.isDamaged(false, DataHealth.DEGRADED, DataHealth.DEGRADED))
        assertFalse(ApnDataSafety.isDamaged(false, DataHealth.UNKNOWN, DataHealth.DEGRADED))
    }

    @Test
    fun `an unknown final state is not treated as damage`() {
        // Better to report "unknown" than to cry wolf about a phone that is fine.
        assertFalse(ApnDataSafety.isDamaged(false, DataHealth.HEALTHY, DataHealth.UNKNOWN))
    }
}
