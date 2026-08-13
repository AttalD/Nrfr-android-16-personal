package com.github.nrfr

import com.github.nrfr.diag.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    private fun value(
        key: String,
        v: String?,
        source: ValueSource,
        mutability: Mutability = Mutability.OUT_OF_SCOPE
    ) = DiagnosticValue(key, key, v, source, mutability, AppVisibility.NO_PERMISSION)

    private fun report(
        values: List<DiagnosticValue> = emptyList(),
        carrierService: String? = null,
        probe: ProbeResult? = null
    ) = DiagnosticReport(
        subId = 1, slot = 1, device = "OnePlus CPH2611", androidRelease = "16",
        sdkInt = 36, securityPatch = "2025-12-01", values = values,
        existingCarrierServicePackage = carrierService, probe = probe
    )

    // ------------------------------------------------------------ probe gate

    @Test
    fun `probe is safe only when no carrier service is bound`() {
        // Displacing an existing carrier app would drop the config it supplies, which is well
        // beyond a diagnostic read.
        assertTrue(report(carrierService = null).probeIsSafe)
        assertTrue(report(carrierService = "").probeIsSafe)
        assertFalse(report(carrierService = "com.example.carrier").probeIsSafe)
    }

    // ------------------------------------------------------------ comparison

    private fun unreadable(key: String, source: ValueSource) = DiagnosticValue(
        key, key, null, source, Mutability.OUT_OF_SCOPE, AppVisibility.NO_PERMISSION,
        error = "SecurityException: getDataNetworkTypeForSubscriber"
    )

    private fun compareOne(
        key: String,
        before: DiagnosticValue,
        after: DiagnosticValue
    ) = ReportFormatter.compare(listOf(before), listOf(after)).single { it.key == key }

    @Test
    fun `becoming readable is an observation artifact, never a mutation`() {
        // Regression test for the false failure seen on a real OnePlus 12R: data_network_type
        // threw SecurityException before the probe and read "5G NR" after, because the probe
        // temporarily held carrier privileges. Nothing about the network changed.
        val c = compareOne(
            "data_network_type",
            unreadable("data_network_type", ValueSource.NETWORK),
            value("data_network_type", "5G NR", ValueSource.NETWORK)
        )
        assertEquals(ComparisonOutcome.BECAME_READABLE, c.outcome)
        assertFalse(c.isMutation)
        assertTrue(c.isNoteworthy)
    }

    @Test
    fun `becoming unreadable is also not a mutation`() {
        val c = compareOne(
            "data_network_type",
            value("data_network_type", "5G NR", ValueSource.NETWORK),
            unreadable("data_network_type", ValueSource.NETWORK)
        )
        assertEquals(ComparisonOutcome.BECAME_UNREADABLE, c.outcome)
        assertFalse(c.isMutation)
    }

    @Test
    fun `unreadable both times is not comparable`() {
        val c = compareOne(
            "data_network_type",
            unreadable("data_network_type", ValueSource.NETWORK),
            unreadable("data_network_type", ValueSource.NETWORK)
        )
        assertEquals(ComparisonOutcome.NOT_COMPARABLE, c.outcome)
        assertFalse(c.isMutation)
    }

    @Test
    fun `a genuinely changed SIM operator is a mutation`() {
        val c = compareOne(
            "sim_operator",
            value("sim_operator", "46000", ValueSource.SIM),
            value("sim_operator", "44010", ValueSource.SIM)
        )
        assertEquals(ComparisonOutcome.CHANGED, c.outcome)
        assertTrue(c.isMutation)
    }

    @Test
    fun `a blanked SIM operator is a mutation`() {
        // The specific hazard of passing null mccmnc to setCarrierTestOverride: the value is still
        // readable, it just became empty. That must not be confused with an unreadable field.
        val c = compareOne(
            "sim_operator",
            value("sim_operator", "46000", ValueSource.SIM),
            value("sim_operator", "", ValueSource.SIM)
        )
        assertEquals(ComparisonOutcome.CHANGED, c.outcome)
        assertTrue(c.isMutation)
    }

    @Test
    fun `all eight required identity keys participate in the verdict`() {
        val required = setOf(
            "sim_operator", "sim_operator_name", "sim_country_iso", "sim_carrier_id",
            "network_operator", "network_operator_name", "network_country_iso", "network_roaming"
        )
        assertEquals(required, ReportFormatter.IDENTITY_KEYS)
    }

    @Test
    fun `non-identity changes are reported but do not fail the verdict`() {
        // Radio state legitimately fluctuates (cell reselection, 5G to LTE handover).
        val c = compareOne(
            "data_network_type",
            value("data_network_type", "5G NR", ValueSource.NETWORK),
            value("data_network_type", "4G LTE", ValueSource.NETWORK)
        )
        assertEquals(ComparisonOutcome.CHANGED, c.outcome)
        assertFalse("radio state is not an identity claim", c.isMutation)
        assertTrue(c.isNoteworthy)
    }

    @Test
    fun `identical identity values compare as unchanged`() {
        val c = compareOne(
            "network_country_iso",
            value("network_country_iso", "cn", ValueSource.NETWORK),
            value("network_country_iso", "cn", ValueSource.NETWORK)
        )
        assertEquals(ComparisonOutcome.UNCHANGED, c.outcome)
        assertFalse(c.isMutation)
        assertFalse(c.isNoteworthy)
    }

    @Test
    fun `describe wording marks a readability artifact as not a mutation`() {
        val text = ReportFormatter.describe(
            compareOne(
                "data_network_type",
                unreadable("data_network_type", ValueSource.NETWORK),
                value("data_network_type", "5G NR", ValueSource.NETWORK)
            )
        )
        assertTrue(text.contains("data_network_type"))
        assertTrue(text.contains("不算变更"))
    }

    // ------------------------------------------------------------ probe result

    private fun probe(
        invoked: Boolean,
        applied: Boolean,
        comparisons: List<ValueComparison> = emptyList()
    ) = ProbeResult(listOf(ProbeStep("register", true)), invoked, applied, comparisons)

    @Test
    fun `mechanism works when the framework called back and merged our config`() {
        assertTrue(probe(invoked = true, applied = true).mechanismWorks)
        // A registered provider that is never called back is not a working mechanism.
        assertFalse(probe(invoked = false, applied = true).mechanismWorks)
        // Called back but the config never landed is likewise a failure.
        assertFalse(probe(invoked = true, applied = false).mechanismWorks)
    }

    @Test
    fun `mechanism verdict is independent of revert hygiene`() {
        // The core regression: a dirty revert is a separate problem and must never be reported
        // as "the Android 16 mechanism is unavailable".
        val dirty = probe(
            invoked = true, applied = true,
            comparisons = listOf(
                ValueComparison(
                    "sim_operator", "sim_operator", ComparisonOutcome.CHANGED,
                    "46000", "44010", isIdentity = true
                )
            )
        )
        assertTrue("mechanism still demonstrated", dirty.mechanismWorks)
        assertFalse("but revert was not clean", dirty.revertClean)
        assertFalse(dirty.succeeded)
    }

    @Test
    fun `a readability artifact alone leaves both verdicts green`() {
        // Exactly the OnePlus 12R case that previously reported failure.
        val p = probe(
            invoked = true, applied = true,
            comparisons = listOf(
                ValueComparison(
                    "data_network_type", "data_network_type", ComparisonOutcome.BECAME_READABLE,
                    null, "5G NR", isIdentity = false
                )
            )
        )
        assertTrue(p.mechanismWorks)
        assertTrue(p.revertClean)
        assertTrue(p.succeeded)
        assertEquals(1, p.notes.size)
        assertTrue(p.mutations.isEmpty())
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `report groups values by source and states mutability`() {
        val text = ReportFormatter.format(
            report(
                values = listOf(
                    value("sim_country_iso", "cn", ValueSource.SIM, Mutability.CHANGEABLE),
                    value("network_country_iso", "cn", ValueSource.NETWORK, Mutability.NOT_CHANGEABLE)
                )
            )
        )
        assertTrue(text.contains(ValueSource.SIM.label))
        assertTrue(text.contains(ValueSource.NETWORK.label))
        assertTrue(text.contains("sim_country_iso"))
        assertTrue(text.contains(Mutability.CHANGEABLE.label))
        assertTrue(text.contains(Mutability.NOT_CHANGEABLE.label))
    }

    @Test
    fun `report says explicitly when no probe was run`() {
        assertTrue(ReportFormatter.format(report()).contains("未执行"))
    }

    @Test
    fun `report surfaces a failed revert prominently but still credits the mechanism`() {
        val text = ReportFormatter.format(
            report(
                probe = probe(
                    invoked = true, applied = true,
                    comparisons = listOf(
                        ValueComparison(
                            "sim_operator", "sim_operator", ComparisonOutcome.CHANGED,
                            "46000", "44010", isIdentity = true
                        )
                    )
                )
            )
        )
        assertTrue(text.contains("sim_operator"))
        assertTrue("mechanism must still be reported as available", text.contains("方案在本机可用"))
        assertTrue(text.contains("存在未还原的身份值"))
    }

    @Test
    fun `read failures are displayed as errors rather than silently empty`() {
        val v = DiagnosticValue(
            "x", "X", null, ValueSource.SIM, Mutability.OUT_OF_SCOPE,
            AppVisibility.NO_PERMISSION, error = "SecurityException: nope"
        )
        assertTrue(v.display.contains("读取失败"))
        assertTrue(v.display.contains("SecurityException"))
    }

    @Test
    fun `empty value is distinguishable from a failed read`() {
        assertEquals("(空)", value("k", null, ValueSource.SIM).display)
        assertEquals("(空)", value("k", "", ValueSource.SIM).display)
    }
}
