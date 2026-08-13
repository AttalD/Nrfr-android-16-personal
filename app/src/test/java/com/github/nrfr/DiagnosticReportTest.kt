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

    // ------------------------------------------------------------ identity diff

    @Test
    fun `identity diff ignores non-identity sources`() {
        // Data state and network type legitimately fluctuate on their own; flagging them would
        // make every probe look like it broke something.
        val before = listOf(
            value("sim_operator", "46000", ValueSource.SIM),
            value("data_state", "CONNECTED", ValueSource.FRAMEWORK)
        )
        val after = listOf(
            value("sim_operator", "46000", ValueSource.SIM),
            value("data_state", "DISCONNECTED", ValueSource.FRAMEWORK)
        )
        assertEquals(emptyList<String>(), ReportFormatter.diffIdentity(before, after))
    }

    @Test
    fun `identity diff catches a changed SIM operator`() {
        val before = listOf(value("sim_operator", "46000", ValueSource.SIM))
        val after = listOf(value("sim_operator", "44010", ValueSource.SIM))
        assertEquals(listOf("sim_operator"), ReportFormatter.diffIdentity(before, after))
    }

    @Test
    fun `identity diff catches a value that was blanked`() {
        // The specific hazard of passing null mccmnc to setCarrierTestOverride.
        val before = listOf(value("sim_operator", "46000", ValueSource.SIM))
        val after = listOf(value("sim_operator", null, ValueSource.SIM))
        assertEquals(listOf("sim_operator"), ReportFormatter.diffIdentity(before, after))
    }

    @Test
    fun `identity diff catches network values too`() {
        val before = listOf(value("network_country_iso", "cn", ValueSource.NETWORK))
        val after = listOf(value("network_country_iso", "jp", ValueSource.NETWORK))
        assertEquals(listOf("network_country_iso"), ReportFormatter.diffIdentity(before, after))
    }

    // ------------------------------------------------------------ probe result

    @Test
    fun `probe succeeds only when every part of the chain worked`() {
        fun p(steps: List<ProbeStep>, invoked: Boolean, applied: Boolean) =
            ProbeResult(steps, invoked, applied, true)

        val ok = listOf(ProbeStep("a", true), ProbeStep("b", true))
        assertTrue(p(ok, invoked = true, applied = true).succeeded)

        // A registered provider that is never called back is not a working mechanism.
        assertFalse(p(ok, invoked = false, applied = true).succeeded)
        // Called back but the config never landed is likewise a failure.
        assertFalse(p(ok, invoked = true, applied = false).succeeded)
        assertFalse(p(ok + ProbeStep("c", false), invoked = true, applied = true).succeeded)
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
    fun `report surfaces a failed revert prominently`() {
        val text = ReportFormatter.format(
            report(
                probe = ProbeResult(
                    steps = listOf(ProbeStep("register", true)),
                    onLoadConfigInvoked = true,
                    configApplied = true,
                    identityUnchanged = false,
                    changedValues = listOf("sim_operator")
                )
            )
        )
        assertTrue(text.contains("发生变化的值"))
        assertTrue(text.contains("sim_operator"))
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
