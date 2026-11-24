package com.yearwall.app.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.yearwall.app.data.YearwallStore

/**
 * The one worker all three timers share. It re-arms the midnight chain itself at the end of
 * every run — a worker running under the fallback/catch-up names still owns the next-midnight
 * booking, because "now is the new day" is first true right here.
 */
class YearwallWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        return try {
            val force = inputData.getBoolean(KEY_FORCE, false)
            val prefs = YearwallStore.read(appContext)
            if (!force && !prefs.enabled) {
                Scheduler.cancelAll(appContext)
                return Result.success()
            }
            val outcome = YearwallEngine.apply(appContext, force)
            Scheduler.scheduleNextMidnight(appContext, ExistingWorkPolicy.REPLACE)
            when {
                outcome.allOk -> Result.success()
                // A rejected wallpaper set is most likely permanent (launcher denies it), but
                // OEMs misbehave transiently under boot pressure too — retry, then give up and
                // surface the reason via StatusChecker's last-error item.
                runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        } catch (t: Throwable) {
            YearwallStore.setLastError(appContext, t.message ?: t.javaClass.simpleName)
            if (YearwallStore.read(appContext).enabled) {
                Scheduler.scheduleNextMidnight(appContext, ExistingWorkPolicy.REPLACE)
            } else {
                Scheduler.cancelAll(appContext)
            }
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val MAX_ATTEMPTS = 3
    }
}
