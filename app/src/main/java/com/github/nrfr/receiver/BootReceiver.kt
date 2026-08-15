package com.github.nrfr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.nrfr.data.OverrideStore
import com.github.nrfr.manager.CarrierConfigManager
import com.github.nrfr.region.ProfileManager
import com.github.nrfr.region.TransactionJournal

/**
 * 开机后尝试重新应用配置。
 *
 * Neither privileged call survives a reboot — `setCarrierTestOverride` and
 * `setCarrierServicePackageOverride` are in-memory framework state — so the override must be
 * re-applied on every boot.
 *
 * This is best effort by design: on a non-rooted device Shizuku itself has to be restarted
 * manually after a reboot, so this receiver will usually fail and the real re-apply happens when
 * the user next opens Nrfr (see `MainActivity`). It succeeds when Shizuku is already running
 * (e.g. started by root or by Wireless-debugging auto-start).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        // A reboot clears every in-memory privileged override, so a record left in ACTIVE is not
        // an error — it simply describes a state that no longer exists. Reconcile each record
        // against the framework (which closes those cleanly) rather than blindly "rolling back".
        TransactionJournal.openTransactions(context).forEach { tx ->
            runCatching { ProfileManager.reconcile(context, tx.slot, tx.subId) }
                .onSuccess { Log.i(TAG, "boot reconcile subId=${tx.subId} -> $it") }
                .onFailure { Log.i(TAG, "boot reconcile not possible yet: ${it.message}") }
        }

        if (OverrideStore.configuredSubIds(context).isEmpty()) return

        runCatching { CarrierConfigManager.reapplyAll(context) }
            .onSuccess { Log.i(TAG, "boot re-apply: $it") }
            .onFailure { Log.i(TAG, "boot re-apply not possible yet: ${it.message}") }
    }

    private companion object {
        const val TAG = "Nrfr/Boot"
    }
}
