package com.yearwall.app.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
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
    private const val ALARM_REQUEST = 4444

    fun scheduleNextMidnight(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<YearwallWorker>()
            .setInitialDelay(Duration.ofMillis(millisUntilNextMidnight()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_MIDNIGHT, policy, request)
        scheduleExactMidnightAlarm(context)
    }

    /*
     * WorkManager chains are Doze-deferred: with the phone locked and idle overnight the job
     * lands in a system-chosen maintenance window, so the theoretical midnight run can slip
     * hours (observed: until first unlock on both test devices). The exact alarm is the
     * platform mechanism that breaks THROUGH Doze — setExactAndAllowWhileIdle wakes the CPU
     * at precisely 00:00:10 and the receiver applies under a short wakelock. WorkManager
     * stays as the crash-proof long chain; the alarm is the sharp edge.
     */
    fun scheduleExactMidnightAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, YearwallAlarmReceiver::class.java)
            .setAction(YearwallAlarmReceiver.ACTION_MIDNIGHT)
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = System.currentTimeMillis() + millisUntilNextMidnight()
        val canExact = exactAlarmsPermitted(context)
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                // No exact-alarm permission: best-effort window instead — same as the old
                // behavior plus a nudge to grant the permission for exact timing.
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, 600_000L, pendingIntent)
            }
        } catch (t: Throwable) {
            Log.e("YearwallAlarm", "scheduling midnight alarm failed", t)
        }
    }

    fun cancelExactMidnightAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, YearwallAlarmReceiver::class.java)
            .setAction(YearwallAlarmReceiver.ACTION_MIDNIGHT)
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { alarmManager.cancel(pendingIntent) }
    }

    fun exactAlarmsPermitted(context: Context): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // pre-31: no opt-in needed
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
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
        // A CANCELLED periodic row blocks KEEP too; UPDATE re-arms it at once (same request
        // content, so this churns nothing), keeping the safety net alive after Remove+re-Apply.
        val request = PeriodicWorkRequestBuilder<YearwallWorker>(Duration.ofHours(24))
            .setInitialDelay(Duration.ofMillis(millisUntilNextMidnight()))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_FALLBACK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Re-arms every layer. [rebaseExisting] is what the boot/time receivers pass: a pending
     * midnight job was armed against the *old* clock/timezone, so it's replaced with a delay
     * recomputed against the new one. The app-open path passes false — KEEP keeps the existing
     * chain intact instead of pushing "next midnight" out a full day on every resume.
     */
    fun ensure(context: Context, rebaseExisting: Boolean = false) {
        scheduleNextMidnight(context, midnightPolicy(context, rebaseExisting))
        ensureFallback(context)
        enqueueCatchUp(context)
    }

    /*
     * KEEP only protects a chain that is genuinely alive. After `Remove` (cancelAll) the
     * midnight record survives internally in CANCELLED state — KEEP would see it and refuse
     * to arm anything, leaving the status red until some future worker re-armed with REPLACE.
     * So: ENQUEUED → keep (don't churn), RUNNING → keep (never yank an in-flight midnight
     * run), anything else (cancelled/finished/absent) → replace, which re-arms immediately.
     */
    private fun midnightPolicy(context: Context, rebaseExisting: Boolean): ExistingWorkPolicy {
        if (rebaseExisting) return ExistingWorkPolicy.REPLACE
        val state = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(UNIQUE_MIDNIGHT).get().firstOrNull()?.state
        }.getOrNull()
        return when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        for (name in listOf(UNIQUE_MIDNIGHT, UNIQUE_CATCHUP, UNIQUE_FALLBACK)) {
            workManager.cancelUniqueWork(name)
        }
        cancelExactMidnightAlarm(context)
    }

    // +10s: cron fired at exactly 00:00:00 and `date +%j` was already the new day; elapsed-time
    // scheduling needs the buffer so the worker's `LocalDate.now()` can't race the flip.
    private fun millisUntilNextMidnight(): Long {
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone)
        return Duration.between(ZonedDateTime.now(zone), nextMidnight).toMillis() + 10_000
    }
}
