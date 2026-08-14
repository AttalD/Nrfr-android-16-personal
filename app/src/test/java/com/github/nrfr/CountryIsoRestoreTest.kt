package com.github.nrfr

import com.github.nrfr.diag.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the run #8 rollback bug.
 *
 * `UiccProfile.handleSimCountryIsoOverride()` writes the property **only when the override value
 * is non-empty**, so removing the key is a no-op and `getSimCountryIso()` stays on the overridden
 * value. Restoring means actively pushing the baseline back.
 */
class CountryIsoRestoreTest {

    // --------------------------------------------------------------- decide()

    @Test
    fun `stuck on us with a cn baseline must push the baseline back`() {
        // The exact run #8 situation.
        assertEquals(RestoreAction.PUSH_BASELINE, CountryIsoRestore.decide("cn", "us"))
    }

    @Test
    fun `already on the baseline needs no action`() {
        assertEquals(RestoreAction.NOTHING_TO_DO, CountryIsoRestore.decide("cn", "cn"))
        assertEquals(RestoreAction.NOTHING_TO_DO, CountryIsoRestore.decide("cn", "CN"))
    }

    @Test
    fun `no baseline means we must not guess`() {
        // Writing an assumed "cn" would be fabricating state on someone's phone.
        assertEquals(RestoreAction.CANNOT_RESTORE, CountryIsoRestore.decide(null, "us"))
        assertEquals(RestoreAction.CANNOT_RESTORE, CountryIsoRestore.decide("", "us"))
        assertEquals(RestoreAction.CANNOT_RESTORE, CountryIsoRestore.decide("   ", "us"))
    }

    @Test
    fun `an unreadable current value still triggers a restore attempt`() {
        // Better to push the baseline than to leave it possibly overridden.
        assertEquals(RestoreAction.PUSH_BASELINE, CountryIsoRestore.decide("cn", null))
    }

    // ------------------------------------------------- full lifecycle verdicts

    private fun cmp(key: String, before: String?, after: String?, identity: Boolean = true) =
        ValueComparison(
            key, key,
            if (before == after) ComparisonOutcome.UNCHANGED else ComparisonOutcome.CHANGED,
            before, after, identity
        )

    private fun lifecycle(
        before: String?,
        during: String?,
        after: String?,
        originalKey: String?,
        postKey: String?,
        outcome: RestoreOutcome
    ) = CountryOverrideResult(
        requestedCountry = "us",
        steps = listOf(ProbeStep("register", true)),
        configKeyPresent = true,
        configKeyValue = "us",
        simCountryBefore = before,
        simCountryDuring = during,
        simCountryAfter = after,
        originalConfigKey = originalKey,
        postCleanupConfigKey = postKey,
        restoreOutcome = outcome,
        comparisonsDuring = listOf(cmp("sim_country_iso", before, during)),
        comparisonsAfter = listOf(cmp("sim_country_iso", before, after))
    )

    @Test
    fun `run 8 lifecycle - cn to us then stuck on us is NOT a success`() {
        // baseline cn → apply us → verify us → cleanup → still us. This is the bug; the result
        // type must refuse to call it a success even though the override itself worked.
        val r = lifecycle(
            before = "cn", during = "us", after = "us",
            originalKey = null, postKey = null, outcome = RestoreOutcome.FAILED
        )
        assertEquals(OverrideVerdict.EFFECTIVE, r.verdict)
        assertFalse("country was not restored", r.simCountryRestored)
        assertFalse(r.fullyRestored)
        assertFalse("must not be reported as a clean success", r.cleanSuccess)
    }

    @Test
    fun `fixed lifecycle - cn to us then back to cn is a success`() {
        val r = lifecycle(
            before = "cn", during = "us", after = "cn",
            originalKey = null, postKey = null, outcome = RestoreOutcome.RESTORED
        )
        assertEquals(OverrideVerdict.EFFECTIVE, r.verdict)
        assertTrue(r.simCountryRestored)
        assertTrue(r.configKeyRestored)
        assertTrue(r.fullyRestored)
        assertTrue(r.cleanSuccess)
    }

    @Test
    fun `key absent originally must be absent again after cleanup`() {
        // Requirement 8: cleanup restores the ORIGINAL shape, it does not invent a value.
        val ok = lifecycle(
            before = "cn", during = "us", after = "cn",
            originalKey = null, postKey = null, outcome = RestoreOutcome.RESTORED
        )
        assertTrue(ok.configKeyRestored)

        val leftBehind = lifecycle(
            before = "cn", during = "us", after = "cn",
            originalKey = null, postKey = "cn", outcome = RestoreOutcome.RESTORED
        )
        assertFalse("a key we introduced must not survive cleanup", leftBehind.configKeyRestored)
        assertFalse(leftBehind.cleanSuccess)
    }

    @Test
    fun `a pre-existing key value must be restored exactly`() {
        val restored = lifecycle(
            before = "cn", during = "us", after = "cn",
            originalKey = "cn", postKey = "cn", outcome = RestoreOutcome.RESTORED
        )
        assertTrue(restored.configKeyRestored)

        val wrong = lifecycle(
            before = "cn", during = "us", after = "cn",
            originalKey = "cn", postKey = null, outcome = RestoreOutcome.RESTORED
        )
        assertFalse("we removed a key that was there before us", wrong.configKeyRestored)
    }

    @Test
    fun `restoration is judged on getSimCountryIso, not on the config bundle`() {
        // The precise blind spot in run #8: the bundle looked clean while the property did not.
        val r = lifecycle(
            before = "cn", during = "us", after = "us",
            originalKey = null, postKey = null, outcome = RestoreOutcome.FAILED
        )
        assertTrue("config key looks restored", r.configKeyRestored)
        assertFalse("but the public-facing value is not", r.simCountryRestored)
        assertFalse(r.fullyRestored)
    }

    @Test
    fun `case differences do not count as a failed restore`() {
        val r = lifecycle(
            before = "cn", during = "us", after = "CN",
            originalKey = null, postKey = null, outcome = RestoreOutcome.RESTORED
        )
        assertTrue(r.simCountryRestored)
    }

    @Test
    fun `an unreadable post-cleanup country is not counted as restored`() {
        val r = lifecycle(
            before = "cn", during = "us", after = null,
            originalKey = null, postKey = null, outcome = RestoreOutcome.FAILED
        )
        assertFalse(r.simCountryRestored)
        assertFalse(r.cleanSuccess)
    }
}
