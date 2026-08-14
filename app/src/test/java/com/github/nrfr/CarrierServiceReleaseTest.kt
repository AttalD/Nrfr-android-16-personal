package com.github.nrfr

import com.github.nrfr.diag.*
import com.github.nrfr.region.*
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

    // --------------------------------------------------- transaction integration

    private fun cleanup(released: Boolean) = CleanupReport(
        identityRestored = true,
        carrierConfigRestored = true,
        carrierServiceReleased = released,
        carrierPrivilegesReleased = released,
        noUnexpectedChanges = true,
        apnDataIntact = true
    )

    @Test
    fun `a leftover binding makes cleanup incomplete`() {
        // Exactly run #9: country restored perfectly, but we stayed bound, which blocks the next
        // transaction. That is not a clean run.
        val c = cleanup(released = false)
        assertTrue("identity did come back", c.identityRestored)
        assertFalse("but the binding leaked", c.complete)
        assertTrue(c.failures().any { it.contains("CarrierService") })
    }

    @Test
    fun `releasing the binding completes the cleanup`() {
        assertTrue(cleanup(released = true).complete)
        assertTrue(cleanup(released = true).failures().isEmpty())
    }

    @Test
    fun `a transaction with leaked binding is not a success`() {
        val tx = TransactionResult(
            profile = RegionalProfile.countryOnly("us"),
            steps = listOf(ProbeStep("apply", true)),
            effects = listOf(
                SignalEffect(
                    Signal.SIM_COUNTRY_ISO, "us", "cn", "us",
                    configAccepted = true, outcome = SignalOutcome.EFFECTIVE
                )
            ),
            cleanup = cleanup(released = false),
            applied = true
        )
        assertTrue("the override itself worked", tx.allEffective)
        assertFalse("but the transaction is not a success", tx.success)
    }
}
