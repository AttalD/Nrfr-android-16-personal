package com.github.nrfr

import com.github.nrfr.diag.ComparisonOutcome
import com.github.nrfr.diag.ProbeStep
import com.github.nrfr.diag.ValueComparison
import com.github.nrfr.region.*
import org.junit.Assert.*
import org.junit.Test

class TransactionTest {

    private fun cleanup(
        identity: Boolean = true,
        config: Boolean = true,
        service: Boolean = true,
        privileges: Boolean = true,
        noUnexpected: Boolean = true,
        apn: Boolean = true,
        unexpected: List<ValueComparison> = emptyList()
    ) = CleanupReport(identity, config, service, privileges, noUnexpected, apn, unexpected)

    private fun effect(
        signal: Signal = Signal.SIM_COUNTRY_ISO,
        outcome: SignalOutcome = SignalOutcome.EFFECTIVE,
        accepted: Boolean? = true
    ) = SignalEffect(signal, "us", "cn", "us", accepted, outcome)

    private fun tx(
        effects: List<SignalEffect> = listOf(effect()),
        cleanup: CleanupReport = cleanup(),
        applied: Boolean = true
    ) = TransactionResult(
        RegionalProfile.countryOnly("us"), listOf(ProbeStep("s", true)), effects, cleanup, applied
    )

    // ------------------------------------------------------ cleanup completeness

    @Test
    fun `cleanup requires every single condition`() {
        assertTrue(cleanup().complete)
        assertFalse(cleanup(identity = false).complete)
        assertFalse(cleanup(config = false).complete)
        assertFalse(cleanup(service = false).complete)
        assertFalse(cleanup(privileges = false).complete)
        assertFalse(cleanup(noUnexpected = false).complete)
        assertFalse(cleanup(apn = false).complete)
    }

    @Test
    fun `each unmet condition is named in failures`() {
        assertTrue(cleanup(identity = false).failures().any { it.contains("身份") })
        assertTrue(cleanup(config = false).failures().any { it.contains("CarrierConfig") })
        assertTrue(cleanup(service = false).failures().any { it.contains("CarrierService") })
        assertTrue(cleanup(privileges = false).failures().any { it.contains("privileges") })
        assertTrue(cleanup(apn = false).failures().any { it.contains("APN") })
        assertTrue(cleanup().failures().isEmpty())
    }

    // ------------------------------------------------------------- tx verdicts

    @Test
    fun `success needs both effectiveness and complete cleanup`() {
        assertTrue(tx().success)
        // Worked but leaked — the run #8 and #9 shapes.
        assertFalse(tx(cleanup = cleanup(identity = false)).success)
        assertFalse(tx(cleanup = cleanup(service = false)).success)
        // Cleaned up but never took effect.
        assertFalse(tx(effects = listOf(effect(outcome = SignalOutcome.CONFIG_ACCEPTED_NOT_EFFECTIVE))).success)
        assertFalse(tx(effects = listOf(effect(outcome = SignalOutcome.REJECTED))).success)
    }

    @Test
    fun `a transaction that never applied is not a success but needs no cleanup`() {
        val aborted = tx(effects = emptyList(), applied = false)
        assertFalse(aborted.success)
        assertFalse(aborted.allEffective)
        assertTrue("nothing was touched, so cleanup is trivially complete", aborted.cleanup.complete)
    }

    @Test
    fun `partial effectiveness is not success`() {
        val mixed = tx(
            effects = listOf(
                effect(Signal.SIM_COUNTRY_ISO, SignalOutcome.EFFECTIVE),
                effect(Signal.SIM_OPERATOR_NUMERIC, SignalOutcome.REJECTED, accepted = null)
            )
        )
        assertFalse(mixed.allEffective)
        assertFalse(mixed.success)
    }

    // -------------------------------------------------- accepted vs effective

    @Test
    fun `config accepted but not effective is distinct from rejected`() {
        val accepted = effect(outcome = SignalOutcome.CONFIG_ACCEPTED_NOT_EFFECTIVE, accepted = true)
        val rejected = effect(outcome = SignalOutcome.REJECTED, accepted = false)
        assertNotEquals(accepted.outcome, rejected.outcome)
        assertEquals(true, accepted.configAccepted)
        assertEquals(false, rejected.configAccepted)
    }

    @Test
    fun `mcc mnc has no config-accepted stage`() {
        // No CarrierConfig key exists for it, so "accepted" is meaningless and must be null rather
        // than a misleading false.
        val e = effect(Signal.SIM_OPERATOR_NUMERIC, SignalOutcome.EFFECTIVE, accepted = null)
        assertNull(e.configAccepted)
    }

    // -------------------------------------------------------- unexpected changes

    @Test
    fun `an unexpected network change fails cleanup`() {
        val netChange = ValueComparison(
            "network_country_iso", "网络国家码", ComparisonOutcome.CHANGED, "cn", "us",
            isIdentity = true
        )
        val c = cleanup(noUnexpected = false, unexpected = listOf(netChange))
        assertFalse(c.complete)
        assertEquals(1, c.unexpectedChanges.size)
    }

    @Test
    fun `apn damage fails cleanup independently of identity`() {
        val c = cleanup(apn = false)
        assertTrue(c.identityRestored)
        assertFalse(c.complete)
    }

    @Test
    fun `effect lookup by signal`() {
        val t = tx(
            effects = listOf(
                effect(Signal.SIM_COUNTRY_ISO),
                effect(Signal.SIM_OPERATOR_NAME)
            )
        )
        assertNotNull(t.effect(Signal.SIM_COUNTRY_ISO))
        assertNull(t.effect(Signal.SIM_OPERATOR_NUMERIC))
    }
}
