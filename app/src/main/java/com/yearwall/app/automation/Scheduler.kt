package com.yearwall.app.automation

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * The automation trio of the shell script in WorkManager terms:
 *
 *   crontab "0 0 * * *"           → UNIQUE_MIDNIGHT: one-self-rescheduling one-time job armed
 *                                   for the next local midnight (+10s so the day has flipped)
 *   ~/.termux/boot/50-yearwall.sh → BootReceiver (manifest) calls ensure(rebaseExisting=true)
 *   etc/profile.d catch-up        → catch-up work run on every MainActivity resume
 *
 * UNIQUE_FALLBACK is a 24h periodic insurance layer the script never needed: WorkManager
 * chains can occasionally be dropped, and the staleness check makes an extra same-day run a
 * cheap no-op, so it can't double-generate.
 */
object Scheduler {

    const val UNIQUE_MIDNIGHT = "yearwall_midnight"
    const val UNIQUE_CATCHUP = "yearwall_catchup"
    const val UNIQUE_FALLBACK = "yearwall_daily_fallback"

    fun scheduleNextMidnight(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<YearwallWorker>()
            .setInitialDelay(Duration.ofMillis(millisUntilNextMidnight()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_MIDNIGHT, policy, request)
    }

    fun enqueueCatchUp(context: Context, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<YearwallWorker>()
            .setInputData(workDataOf(YearwallWorker.KEY_FORCE to force))
            .build()
        // REPLACE: the newest catch-up request always wins — repeated enqueues (every app
        // open, every boot) collapse into one pending job instead of stacking.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_CATCHUP, ExistingWorkPolicy.REPLACE, request)
    }

    private fun ensureFallback(context: Context) {
        val request = PeriodicWorkRequestBuilder<YearwallWorker>(Duration.ofHours(24))
            .setInitialDelay(Duration.ofMillis(millisUntilNextMidnight()))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_FALLBACK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Re-arms every layer. [rebaseExisting] is what the boot/time receivers pass: a pending
     * midnight job was armed against the *old* clock/timezone, so it's replaced with a delay
     * recomputed against the new one. The app-open path passes false — KEEP keeps the existing
     * chain intact instead of pushing "next midnight" out a full day on every resume.
     */
    fun ensure(context: Context, rebaseExisting: Boolean = false) {
        scheduleNextMidnight(context, if (rebaseExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        ensureFallback(context)
        enqueueCatchUp(context)
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        for (name in listOf(UNIQUE_MIDNIGHT, UNIQUE_CATCHUP, UNIQUE_FALLBACK)) {
            workManager.cancelUniqueWork(name)
        }
    }

    // +10s: cron fired at exactly 00:00:00 and `date +%j` was already the new day; elapsed-time
    // scheduling needs the buffer so the worker's `LocalDate.now()` can't race the flip.
    private fun millisUntilNextMidnight(): Long {
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone)
        return Duration.between(ZonedDateTime.now(zone), nextMidnight).toMillis() + 10_000
    }
}
