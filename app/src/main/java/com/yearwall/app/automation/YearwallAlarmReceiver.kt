package com.yearwall.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires from the exact midnight alarm (setExactAndAllowWhileIdle) — the only mechanism that
 * runs through Doze while the phone is locked and screen-off. Runs the day's apply under a
 * short partial wakelock, then re-arms everything for tomorrow. Force=false: the staleness
 * check makes it a no-op if the wallpaper is already current (double-fire with the
 * WorkManager chain collapses into one real apply).
 */
class YearwallAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MIDNIGHT) return

        val appContext = context.applicationContext
        val pending = goAsync()
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yearwall:midnight")
        try {
            wakeLock?.acquire(WAKELOCK_MILLIS)
        } catch (_: Throwable) {
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                try {
                    YearwallEngine.apply(appContext, force = false)
                } catch (t: Throwable) {
                    Log.e(TAG, "midnight alarm apply failed", t)
                }
                // Tomorrow's alarm + chains re-arm from here, whatever today's run did.
                Scheduler.ensure(appContext, rebaseExisting = true)
            } finally {
                try { wakeLock?.release() } catch (_: Throwable) { }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.yearwall.app.MIDNIGHT"
        private const val TAG = "YearwallAlarm"
        private const val WAKELOCK_MILLIS = 30_000L
    }
}
