package com.github.nrfr

import com.github.nrfr.manager.FailureKind
import com.github.nrfr.manager.TelephonyFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class TelephonyFailuresTest {

    @Test
    fun `recognises the Android 16 shell block`() {
        // The exact string thrown by CarrierConfigLoader.secureOverrideConfig (AOSP 1ac1e79d1).
        val e = SecurityException("overrideConfig cannot be invoked by shell")
        assertEquals(FailureKind.SHELL_BLOCKED, TelephonyFailures.classify(e))
    }

    @Test
    fun `recognises the persistent-override restriction`() {
        val e = SecurityException(
            "overrideConfig with persistent=true only can be invoked by system app"
        )
        assertEquals(FailureKind.PERSISTENT_REQUIRES_SYSTEM_APP, TelephonyFailures.classify(e))
    }

    @Test
    fun `recognises the user-build key blocklist`() {
        val e = SecurityException(
            "Overriding satellite_entitlement_supported_bool is not allowed on user builds."
        )
        assertEquals(FailureKind.BLOCKLISTED_KEY, TelephonyFailures.classify(e))
    }

    @Test
    fun `unwraps reflection wrappers`() {
        // Every ITelephony call goes through Method.invoke, so the real cause is nested.
        val wrapped = InvocationTargetException(
            SecurityException("overrideConfig cannot be invoked by shell")
        )
        assertEquals(FailureKind.SHELL_BLOCKED, TelephonyFailures.classify(wrapped))
    }

    @Test
    fun `missing hidden API is distinguishable from a permission failure`() {
        assertEquals(
            FailureKind.MISSING_API,
            TelephonyFailures.classify(NoSuchMethodException("setCarrierServicePackageOverride"))
        )
        assertEquals(
            FailureKind.MISSING_API,
            TelephonyFailures.classify(ClassNotFoundException("com.android.internal.telephony.ITelephony"))
        )
    }

    @Test
    fun `unknown failures do not masquerade as something actionable`() {
        assertEquals(FailureKind.UNKNOWN, TelephonyFailures.classify(RuntimeException("boom")))
        assertEquals(FailureKind.UNKNOWN, TelephonyFailures.classify(null))
    }

    @Test
    fun `only the two override restrictions trigger the CarrierService fallback`() {
        assertTrue(FailureKind.SHELL_BLOCKED.shouldFallBackToCarrierService)
        assertTrue(FailureKind.PERSISTENT_REQUIRES_SYSTEM_APP.shouldFallBackToCarrierService)
        // Falling back on these would just fail a second time, or hide a real problem.
        assertFalse(FailureKind.MISSING_API.shouldFallBackToCarrierService)
        assertFalse(FailureKind.NO_PRIVILEGE.shouldFallBackToCarrierService)
        assertFalse(FailureKind.BLOCKLISTED_KEY.shouldFallBackToCarrierService)
    }

    @Test
    fun `every failure kind has a description`() {
        FailureKind.entries.forEach { kind ->
            assertTrue(kind.name, TelephonyFailures.describe(kind).isNotBlank())
        }
    }
}
