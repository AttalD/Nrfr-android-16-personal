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

}
