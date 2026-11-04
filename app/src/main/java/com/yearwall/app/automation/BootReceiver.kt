package com.yearwall.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `~/.termux/boot/50-yearwall.sh` equivalent. WorkManager's database survives reboots — what it
 * can't know is that midnight passed while the device was off, or that a timezone moved the
 * midnight target. So: recompute the next midnight, then run a catch-up apply; the staleness
 * check makes that a no-op when the day is still current.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED -> {
                try {
                    Scheduler.ensure(context, rebaseExisting = true)
                } catch (e: Exception) {
                    // Never let scheduling trouble crash the device's boot path.
                    Log.e(TAG, "Failed to re-arm yearwall automation", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "YearwallBoot"
    }
}
