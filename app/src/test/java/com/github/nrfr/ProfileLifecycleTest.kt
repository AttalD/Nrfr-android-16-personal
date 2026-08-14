package com.github.nrfr

import com.github.nrfr.region.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化 Profile 状态机与崩溃/重启对账。
 *
 * All inputs to [StateReconciler] are values read back from the framework, never assumptions —
 * that is what makes every permutation below testable off-device.
 */
class ProfileLifecycleTest {

    private fun reconcile(
        journal: ProfileState?,
        bound: Boolean,
        matchesProfile: Boolean,
        matchesBaseline: Boolean
    ) = StateReconciler.reconcile(journal, bound, matchesProfile, matchesBaseline)

    // ------------------------------------------------------- transitions

    @Test
    fun `apply is only allowed from inactive`() {
        assertTrue(StateReconciler.canApply(ProfileState.INACTIVE))
        // Re-applying on top of a live override would capture the OVERRIDDEN values as the new
        // baseline and destroy the ability to restore. This is the guard against that.
        assertFalse(StateReconciler.canApply(ProfileState.ACTIVE))
        assertFalse(StateReconciler.canApply(ProfileState.APPLYING))
        assertFalse(StateReconciler.canApply(ProfileState.RESTORING))
        assertFalse(StateReconciler.canApply(ProfileState.RECOVERY_REQUIRED))
    }

    @Test
    fun `restore is allowed from active, failed and recovery_required`() {
        assertTrue(StateReconciler.canRestore(ProfileState.ACTIVE))
        assertTrue(StateReconciler.canRestore(ProfileState.RECOVERY_REQUIRED))
        assertTrue(StateReconciler.canRestore(ProfileState.FAILED))
        assertFalse(StateReconciler.canRestore(ProfileState.INACTIVE))
    }

    @Test
    fun `cleanup outcome decides the resulting state`() {
        assertEquals(ProfileState.INACTIVE, StateReconciler.stateAfterCleanup(true))
        // Never silently return to INACTIVE on an unverified cleanup.
        assertEquals(ProfileState.RECOVERY_REQUIRED, StateReconciler.stateAfterCleanup(false))
    }

    // -------------------------------------------------- steady-state reconcile

    @Test
    fun `no record and nothing bound is simply inactive`() {
        assertEquals(
            ReconcileDecision.NONE_ACTIVE,
            reconcile(null, bound = false, matchesProfile = false, matchesBaseline = true)
        )
    }

    @Test
    fun `no record but still bound means something leaked`() {
        assertEquals(
            ReconcileDecision.NEEDS_CLEANUP,
            reconcile(null, bound = true, matchesProfile = false, matchesBaseline = true)
        )
    }

    @Test
    fun `active and consistent stays active`() {
        assertEquals(
            ReconcileDecision.STILL_ACTIVE,
            reconcile(ProfileState.ACTIVE, bound = true, matchesProfile = true, matchesBaseline = false)
        )
    }

    // ------------------------------------------------------------- reboot

    @Test
    fun `reboot while active resolves to inactive, not an error`() {
        // Both privileged overrides are in-memory, so a reboot wipes them. The values are back at
        // the baseline and we are unbound: there is nothing to undo, just a record to close.
        assertEquals(
            ReconcileDecision.NONE_ACTIVE,
            reconcile(ProfileState.ACTIVE, bound = false, matchesProfile = false, matchesBaseline = true)
        )
    }

    @Test
    fun `unbound but values still overridden needs cleanup`() {
        // The run #8 shape: country stuck on the override with no binding left to push it back.
        assertEquals(
            ReconcileDecision.NEEDS_CLEANUP,
            reconcile(ProfileState.ACTIVE, bound = false, matchesProfile = true, matchesBaseline = false)
        )
    }

    @Test
    fun `bound but values drifted needs cleanup`() {
        assertEquals(
            ReconcileDecision.NEEDS_CLEANUP,
            reconcile(ProfileState.ACTIVE, bound = true, matchesProfile = false, matchesBaseline = false)
        )
    }

    // -------------------------------------------------------------- crashes

    @Test
    fun `crash during apply is cleaned up`() {
        assertEquals(
            ReconcileDecision.NEEDS_CLEANUP,
            reconcile(ProfileState.APPLYING, bound = true, matchesProfile = true, matchesBaseline = false)
        )
    }

    @Test
    fun `crash during apply before anything landed needs no cleanup`() {
        assertEquals(
            ReconcileDecision.NONE_ACTIVE,
            reconcile(ProfileState.APPLYING, bound = false, matchesProfile = false, matchesBaseline = true)
        )
    }

    @Test
    fun `crash during restore is cleaned up`() {
        assertEquals(
            ReconcileDecision.NEEDS_CLEANUP,
            reconcile(ProfileState.RESTORING, bound = true, matchesProfile = false, matchesBaseline = false)
        )
    }

    @Test
    fun `reboot during restore that already completed is inactive`() {
        assertEquals(
            ReconcileDecision.NONE_ACTIVE,
            reconcile(ProfileState.RESTORING, bound = false, matchesProfile = false, matchesBaseline = true)
        )
    }

    @Test
    fun `recovery_required is never auto-resolved`() {
        // Deliberately sticky: guessing here is how a phone ends up half-modified.
        assertEquals(
            ReconcileDecision.RECOVERY_REQUIRED,
            reconcile(ProfileState.RECOVERY_REQUIRED, bound = false, matchesProfile = false, matchesBaseline = true)
        )
        assertEquals(
            ReconcileDecision.RECOVERY_REQUIRED,
            reconcile(ProfileState.RECOVERY_REQUIRED, bound = true, matchesProfile = true, matchesBaseline = false)
        )
    }

    @Test
    fun `repeated reconcile is idempotent`() {
        // Running recovery twice must not cause a second round of changes.
        val first = reconcile(ProfileState.ACTIVE, bound = false, matchesProfile = false, matchesBaseline = true)
        val second = reconcile(null, bound = false, matchesProfile = false, matchesBaseline = true)
        assertEquals(ReconcileDecision.NONE_ACTIVE, first)
        assertEquals(ReconcileDecision.NONE_ACTIVE, second)
    }

    // ------------------------------------------------------ journal round trip

    @Test
    fun `journal carries state and applied profile across process death`() {
        val tx = OpenTransaction(
            subId = 2, slot = 0, profileName = "美国 T-Mobile",
            baselineCountryIso = "cn",
            baselineOperatorNumeric = "46000",
            baselineOperatorName = "CMCC",
            baselineConfigCountryKey = null,
            startedAtMillis = 1L,
            state = ProfileState.ACTIVE,
            appliedCountryIso = "us",
            appliedOperatorName = "T-Mobile",
            appliedOperatorNumeric = "310260"
        )
        val back = OpenTransaction.fromJson(tx.toJson())!!
        assertEquals(ProfileState.ACTIVE, back.state)
        assertEquals("310260", back.appliedOperatorNumeric)
        // The baseline is what restoration needs and must survive exactly.
        assertEquals("46000", back.baselineOperatorNumeric)
        assertEquals("CMCC", back.baselineOperatorName)
        assertEquals("cn", back.baselineCountryIso)
        assertNull("absent config key must stay absent", back.baselineConfigCountryKey)
    }

    @Test
    fun `a record written by an older build defaults to APPLYING rather than ACTIVE`() {
        // Fail safe: an unknown/missing state must never be read as "already active and fine".
        val legacy = """{"subId":2,"slot":0,"profileName":"x","startedAtMillis":1}"""
        assertEquals(ProfileState.APPLYING, OpenTransaction.fromJson(legacy)!!.state)
    }

    @Test
    fun `updating state must not disturb the baseline`() {
        val tx = OpenTransaction(
            subId = 2, slot = 0, profileName = "p",
            baselineCountryIso = "cn", baselineOperatorNumeric = "46000",
            baselineOperatorName = "CMCC", baselineConfigCountryKey = null,
            startedAtMillis = 1L, state = ProfileState.APPLYING
        )
        val updated = tx.copy(state = ProfileState.ACTIVE)
        assertEquals("cn", updated.baselineCountryIso)
        assertEquals("46000", updated.baselineOperatorNumeric)
        assertEquals("CMCC", updated.baselineOperatorName)
    }

    // --------------------------------------------------------- profile shape

    @Test
    fun `the production T-Mobile profile is the verified one`() {
        val p = RegionalProfile.PRESETS.first { it.operatorNumeric == "310260" }
        assertEquals("us", p.countryIso)
        assertEquals("T-Mobile", p.operatorName)
        assertEquals("310260", p.operatorNumeric)
        assertTrue(p.validate().isEmpty())
    }

    @Test
    fun `the lifecycle is not hard-coded to T-Mobile`() {
        // Any profile must be applicable through the same machinery.
        RegionalProfile.PRESETS.forEach { p ->
            assertTrue("${p.name} should validate", p.validate().isEmpty())
            assertTrue("${p.name} must touch at least one signal", p.touchedSignals().isNotEmpty())
        }
        val custom = RegionalProfile(name = "自定义", countryIso = "jp", operatorNumeric = "44010")
        assertTrue(custom.validate().isEmpty())
        assertTrue(Signal.SIM_OPERATOR_NUMERIC in custom.touchedSignals())
    }

    @Test
    fun `network signals are never writable by any profile`() {
        RegionalProfile.PRESETS.forEach { p ->
            assertFalse(Signal.NETWORK_OPERATOR_NUMERIC in p.touchedSignals())
            assertFalse(Signal.NETWORK_COUNTRY_ISO in p.touchedSignals())
            assertFalse(Signal.NETWORK_OPERATOR_NAME in p.touchedSignals())
        }
    }
}
