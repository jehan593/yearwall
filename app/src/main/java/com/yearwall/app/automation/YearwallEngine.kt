package com.yearwall.app.automation

import android.content.Context
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.yearwall.app.data.YearwallStore
import com.yearwall.app.wallpaper.ApplyOutcome
import com.yearwall.app.wallpaper.WallpaperApplier
import com.yearwall.app.wallpaper.WallpaperGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/*
 * The single code path every trigger funnels into — midnight worker, catch-up workers, the
 * 24h fallback and the Apply-now button all land here (the script's yearwall_update.sh body).
 *
 * force=false (automated paths) skips the work if today's wallpaper was already applied —
 * the boot script's `date -r` staleness check. force=true (the button) renders regardless.
 */
object YearwallEngine {

    const val IMAGE_FILE = "yearwall_generated.png"

    suspend fun apply(context: Context, force: Boolean = false): ApplyOutcome {
        val today = LocalDate.now()
        return try {
            applyRender(context, today, force)
        } catch (t: Throwable) {
            // Low-RAM devices can throw OutOfMemoryError (an Error, not Exception) from inside
            // the system's wallpaper scaling — the caller (UI or worker) must never crash from it.
            val message = t.message ?: t.javaClass.simpleName
            try { YearwallStore.setLastError(context, message) } catch (_: Throwable) { }
            ApplyOutcome(false, false, listOf(message))
        }
    }

    private suspend fun applyRender(context: Context, today: LocalDate, force: Boolean): ApplyOutcome {
        val prefs = YearwallStore.read(context)
        if (!force && !prefs.enabled) {
            return ApplyOutcome(true, true, listOf("Yearwall is removed; automatic updates are disabled."))
        }
        if (force && !prefs.enabled) {
            YearwallStore.setEnabled(context, true)
        }
        val targetFlags = WallpaperApplier.flagsFor(prefs.target)
        val homeTargeted = targetFlags.contains(WallpaperManager.FLAG_SYSTEM)
        val lockTargeted = targetFlags.contains(WallpaperManager.FLAG_LOCK)

        // A screen holding the render but no longer targeted goes back to the bundled Nord
        // image on this pass — otherwise it keeps a render yearwall will never refresh again.
        val resetFlags = buildList {
            if (prefs.homeHasYearwall && !homeTargeted) add(WallpaperManager.FLAG_SYSTEM)
            if (prefs.lockHasYearwall && !lockTargeted) add(WallpaperManager.FLAG_LOCK)
        }
        // A target change (or a partially failed previous apply) makes the stored screen set
        // disagree with the target, so it must not be swallowed by the same-day staleness gate.
        val screensMatchTarget =
            prefs.homeHasYearwall == homeTargeted && prefs.lockHasYearwall == lockTargeted
        if (!force && prefs.lastAppliedEpochDay == today.toEpochDay() && screensMatchTarget) {
            return ApplyOutcome(true, true, listOf("Wallpaper is already current for $today."))
        }

        val reset = if (resetFlags.isEmpty()) null else WallpaperApplier.restoreScreens(context, resetFlags)

        val design = run {
            // Real display size — desiredMinimumWidth/Height lies on several skins (a tiny
            // render gets upscaled ~4x), so window metrics are used instead.
            val (detectedWidth, detectedHeight) = screenPortraitSize(context)
            WallpaperGenerator.Design(
                width = detectedWidth,
                height = detectedHeight,
                columns = prefs.columns,
                contentYRatio = prefs.contentYPercent / 100f, // percent → ratio (29 → 0.29)
            )
        }
        val bitmap = withContext(Dispatchers.Default) {
            WallpaperGenerator.generate(context, today, design)
        }
        val image = File(context.filesDir, IMAGE_FILE)
        withContext(Dispatchers.IO) {
            FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val outcome = WallpaperApplier.apply(context, bitmap, prefs.target)
        val messages = reset?.messages.orEmpty() + outcome.messages
        android.util.Log.i("Yearwall", "apply complete home=${outcome.homeOk} lock=${outcome.lockOk} reset=$resetFlags ${messages.joinToString(" | ")}")

        // Remember what is actually on each screen, including partial failures, so the next
        // apply retries a failed write/reset instead of forgetting that screen exists.
        var homeHas = prefs.homeHasYearwall
        var lockHas = prefs.lockHasYearwall
        if (resetFlags.contains(WallpaperManager.FLAG_SYSTEM) && reset?.homeOk == true) homeHas = false
        if (resetFlags.contains(WallpaperManager.FLAG_LOCK) && reset?.lockOk == true) lockHas = false
        if (homeTargeted && outcome.homeOk) homeHas = true
        if (lockTargeted && outcome.lockOk) lockHas = true
        YearwallStore.setAppliedScreens(context, homeHas, lockHas)

        // Only stamp the day when the new render AND every dropped-screen reset took — a failed
        // lock write under target=lock still retries next time (the script's `|| exit 1`).
        val allOk = outcome.allOk && (reset?.allOk ?: true)
        if (allOk) {
            YearwallStore.setLastAppliedDay(context, today.toEpochDay())
            // Re-arm the midnight chain on EVERY successful apply — the Apply-now button path
            // runs outside the worker, and without this a Remove+Apply in the same Activity
            // session left the chain cancelled and the status red until some later worker
            // happened to re-arm it (verified live on the device).
            Scheduler.scheduleNextMidnight(context, androidx.work.ExistingWorkPolicy.REPLACE)
        }
        YearwallStore.setLastError(context, if (allOk) null else messages.joinToString(" · "))
        return outcome.copy(
            homeOk = outcome.homeOk && (reset?.homeOk ?: true),
            lockOk = outcome.lockOk && (reset?.lockOk ?: true),
            messages = messages
        )
    }

    /**
     * The `rm` command: stop all timers/alarms, restore the bundled Nord default on every
     * screen yearwall touched — including one dropped by a target change that has not applied
     * yet — then wipe generated artifacts and prefs so nothing can auto-apply until Apply.
     */
    suspend fun remove(context: Context): List<String> {
        val prefs = YearwallStore.read(context)
        val flags = WallpaperApplier.flagsFor(prefs.target).toMutableList()
        if (prefs.homeHasYearwall && !flags.contains(WallpaperManager.FLAG_SYSTEM)) {
            flags += WallpaperManager.FLAG_SYSTEM
        }
        if (prefs.lockHasYearwall && !flags.contains(WallpaperManager.FLAG_LOCK)) {
            flags += WallpaperManager.FLAG_LOCK
        }
        Scheduler.cancelAll(context)
        val restored = WallpaperApplier.restoreScreens(context, flags)
        File(context.filesDir, IMAGE_FILE).delete()
        YearwallStore.clear(context)
        if (!restored.allOk) {
            YearwallStore.setLastError(context, restored.messages.joinToString(" · "))
        }
        return restored.messages
    }

    /**
     * Real display dimensions, portrait-normalized (a wallpaper is conventionally portrait;
     * the current orientation of the worker's process shouldn't dictate it). maximumWindowMetrics
     * gives the true panel size on API 30+; getRealSize covers 26–29. The script's 1080x2400
     * reference only survives as the never-reachable-services fallback.
     */
    internal fun screenPortraitSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            val point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                Point().also { display?.getRealSize(it) }
            }
            if (point.x > 0 && point.y > 0) {
                return if (point.x > point.y) point.y to point.x else point.x to point.y
            }
        }
        return 1080 to 2400
    }
}
