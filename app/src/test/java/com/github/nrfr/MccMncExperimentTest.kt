package com.github.nrfr

import com.github.nrfr.diag.ComparisonOutcome
import com.github.nrfr.diag.ProbeStep
import com.github.nrfr.diag.ReportFormatter
import com.github.nrfr.diag.ValueComparison
import com.github.nrfr.region.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 美国 T-Mobile（310260）MCC/MNC 实验的判定逻辑。
 *
 * The device baseline this is written against: OnePlus 12R, SIM 46000 / CMCC, Carrier ID 1435,
 * network 46000 / cn, APN CMNET / cmnet, data healthy.
 */
class MccMncExperimentTest {

    private val profile = RegionalProfile.PRESETS.first { it.operatorNumeric == "310260" }

    private fun cmp(key: String, before: String?, after: String?, identity: Boolean = true) =
        ValueComparison(
            key, key,
            if (before == after) ComparisonOutcome.UNCHANGED else ComparisonOutcome.CHANGED,
            before, after, identity
        )

    private fun cleanup(
        identityRestored: Boolean = true,
        noUnexpected: Boolean = true,
        apnIntact: Boolean = true,
        unexpected: List<ValueComparison> = emptyList()
    ) = CleanupReport(
        identityRestored = identityRestored,
        carrierConfigRestored = true,
        carrierServiceReleased = true,
        carrierPrivilegesReleased = true,
        noUnexpectedChanges = noUnexpected,
        apnDataIntact = apnIntact,
        unexpectedChanges = unexpected
    )

    private fun result(
        numericOutcome: SignalOutcome = SignalOutcome.EFFECTIVE,
        during: String? = "310260",
        cleanup: CleanupReport = cleanup()
    ) = TransactionResult(
        profile = profile,
        steps = listOf(ProbeStep("apply", true)),
        effects = listOf(
            SignalEffect(
                Signal.SIM_COUNTRY_ISO, "us", "cn", "us",
                configAccepted = true, outcome = SignalOutcome.EFFECTIVE
            ),
            SignalEffect(
                Signal.SIM_OPERATOR_NAME, "T-Mobile", "CMCC", "T-Mobile",
                configAccepted = true, outcome = SignalOutcome.EFFECTIVE
            ),
            SignalEffect(
                Signal.SIM_OPERATOR_NUMERIC, "310260", "46000", during,
                configAccepted = null, outcome = numericOutcome
            )
        ),
        cleanup = cleanup,
        applied = true
    )

    // ------------------------------------------------------------ profile shape

    @Test
    fun `the T-Mobile preset targets 310260 and stays experimental`() {
        assertEquals("310260", profile.operatorNumeric)
        assertEquals("us", profile.countryIso)
        assertTrue(profile.usesExperimentalMechanism())
        assertTrue(profile.validate().isEmpty())
    }

    @Test
    fun `MCC MNC goes through setCarrierTestOverride, never CarrierConfig`() {
        // There is no CarrierConfig key for the operator numeric; claiming otherwise would be
        // pretending the proven country mechanism covers this too.
        assertEquals(
            Mechanism.CARRIER_TEST_OVERRIDE,
            SignalCapabilities[Signal.SIM_OPERATOR_NUMERIC].signal.mechanism
        )
    }

    @Test
    fun `carrier id is an expected change, not an accident`() {
        // CarrierResolver derives it from the SIM operator numeric, so it moves whether we want
        // it to or not — it must be pre-declared so cleanup does not call it a side effect.
        assertTrue(Signal.SIM_CARRIER_ID in profile.touchedSignals())
        assertTrue(profile.expectedChangeKeys().contains(Signal.SIM_CARRIER_ID.key))
    }

    @Test
    fun `apn reselection is expected during an MCC MNC run`() {
        // APN matching keys off the operator numeric, so a changed APN mid-transaction is normal.
        assertTrue(profile.expectedChangeKeys().contains(Signal.APN_DATA.key))
        // …but never for a country-only run.
        assertFalse(
            RegionalProfile.countryOnly("us").expectedChangeKeys().contains(Signal.APN_DATA.key)
        )
    }

    @Test
    fun `the network side is never a target`() {
        val touched = profile.touchedSignals()
        assertFalse(Signal.NETWORK_OPERATOR_NUMERIC in touched)
        assertFalse(Signal.NETWORK_COUNTRY_ISO in touched)
        assertFalse(Signal.NETWORK_OPERATOR_NAME in touched)
    }

    // --------------------------------------------------------- effectiveness

    @Test
    fun `MCC MNC counts as effective only when read back as 310260`() {
        assertEquals(SignalOutcome.EFFECTIVE, result().effect(Signal.SIM_OPERATOR_NUMERIC)!!.outcome)
        assertTrue(result().allEffective)
    }

    @Test
    fun `an unmoved MCC MNC is rejected, not quietly accepted`() {
        val r = result(numericOutcome = SignalOutcome.REJECTED, during = "46000")
        assertFalse(r.allEffective)
        assertFalse(r.success)
    }

    @Test
    fun `there is no config-accepted stage for MCC MNC`() {
        assertEquals(null, result().effect(Signal.SIM_OPERATOR_NUMERIC)!!.configAccepted)
    }

    // -------------------------------------------------------------- rollback

    @Test
    fun `an effective override with a failed rollback is not a success`() {
        val r = result(
            cleanup = cleanup(
                identityRestored = false,
                noUnexpected = false,
                unexpected = listOf(cmp("sim_operator", "46000", "310260"))
            )
        )
        assertTrue("the override worked", r.allEffective)
        assertFalse("but it did not come back", r.success)
        assertTrue(r.cleanup.failures().isNotEmpty())
    }

    @Test
    fun `a fully restored run is a success`() {
        assertTrue(result().success)
        assertTrue(result().cleanup.complete)
    }

    @Test
    fun `lost data connectivity fails the run even if everything restored`() {
        // Safety must not be weakened to make the experiment pass.
        val r = result(cleanup = cleanup(apnIntact = false))
        assertFalse(r.success)
        assertTrue(r.cleanup.failures().any { it.contains("APN") || it.contains("数据") })
    }

    @Test
    fun `a leftover carrier id after rollback is a restore failure`() {
        // Carrier ID is expected to move *during* the run, but must be back afterwards; cleanup
        // compares against an empty expected set precisely so this cannot be masked.
        val r = result(
            cleanup = cleanup(
                identityRestored = false,
                noUnexpected = false,
                unexpected = listOf(cmp("sim_carrier_id", "1435", "1"))
            )
        )
        assertFalse(r.success)
    }

    // ----------------------------------------------------------- reporting

    @Test
    fun `the report states observations when present`() {
        val r = result().copy(
            observations = listOf(
                "SIM MCC/MNC: 46000 → 310260 （已变为目标值 310260 ✅）",
                "网络 MCC/MNC: 46000 → 46000 （未变 ✅ 只读观测）"
            )
        )
        val text = ReportFormatter.formatTransaction(r)
        assertTrue(text.contains("实验观察"))
        assertTrue(text.contains("310260"))
        assertTrue(text.contains("网络 MCC/MNC"))
    }

    @Test
    fun `the report never claims MCC MNC went through carrier config`() {
        val text = ReportFormatter.formatTransaction(result())
        assertTrue(text.contains("不适用（该机制不经 CarrierConfig）"))
    }
}
