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
        if (!force && prefs.lastAppliedEpochDay == today.toEpochDay()) {
            return ApplyOutcome(true, true, listOf("Wallpaper is already current for $today."))
        }

        val design = run {
            // Generalized sizing — from the REAL display size. WallpaperManager's
            // desiredMinimumWidth/Height is a documented trap: several skins return a
            // virtual/launcher-scaled value there (a tiny bitmap gets upscaled ~4x by the
            // lock screen into giant dots), so the actual window metrics are used instead,
            // with the script's 1080x2400 reference as the last-resort fallback.
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
        // Only stamp the day when every targeted screen actually took — a failed lock-screen
        // write under target=lock still re-tries the pair next time (the script's `|| exit 1`).
        if (outcome.allOk) YearwallStore.setLastAppliedDay(context, today.toEpochDay())
        YearwallStore.setLastError(
            context,
            if (outcome.allOk) null else outcome.messages.joinToString(" · ")
        )
        return outcome
    }

    /**
     * The `rm` command: stop all three timers, restore what was set before the very first
     * apply (the script restored a fixed default — this restores *your actual* previous
     * wallpaper), then wipe generated artifacts and prefs.
     */
    suspend fun remove(context: Context): List<String> {
        val messages = WallpaperApplier.removeAndRestore(context)
        File(context.filesDir, IMAGE_FILE).delete()
        YearwallStore.clear(context)
        Scheduler.cancelAll(context)
        return messages
    }

    /**
     * Real display dimensions, portrait-normalized (a wallpaper is conventionally portrait;
     * the current orientation of the worker's process shouldn't dictate it). maximumWindowMetrics
     * gives the true panel size on API 30+; getRealSize covers 26–29. The script's 1080x2400
     * reference only survives as the never-reachable-services fallback.
     */
    private fun screenPortraitSize(context: Context): Pair<Int, Int> {
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
