package com.github.nrfr.diag

import java.util.concurrent.atomic.AtomicInteger

/**
 * [com.github.nrfr.service.NrfrCarrierService] 与探测逻辑之间的桥梁。
 *
 * The service is bound by the *system*, but it runs in this app's own process (no
 * `android:process` in the manifest), so plain static state is a valid channel between the
 * framework callback and the probe that is waiting for it.
 */
object CarrierServiceBridge {

    /** Carrier config key used only by the probe. Not a framework key — the framework ignores it. */
    const val PROBE_KEY = "nrfr_probe_token"

    /**
     * When non-null, the service serves **only** this token and nothing else.
     *
     * That is what makes the probe identity-neutral: we prove the framework fetches and applies
     * our bundle without putting a single real telephony value in it.
     */
    @Volatile
    var probeToken: String? = null

    private val invocations = AtomicInteger(0)

    @Volatile
    var lastSubId: Int = -1
        private set

    fun onLoadConfigCalled(subId: Int) {
        lastSubId = subId
        invocations.incrementAndGet()
    }

    fun invocationCount(): Int = invocations.get()

    fun reset() {
        probeToken = null
        lastSubId = -1
        invocations.set(0)
    }
}
