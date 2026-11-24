package com.yearwall.app.automation

import android.app.WallpaperManager
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yearwall.app.data.YearwallStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate

/*
 * The status screen — one check per layer, same as the shell script's status command:
 * what each part does, whether it's healthy, and why not when it isn't.
 */
data class StatusItem(val title: String, val ok: Boolean, val detail: String)

object StatusChecker {

    private val DATE_FORMAT = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())

    suspend fun collect(context: Context): List<StatusItem> = withContext(Dispatchers.IO) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val prefs = YearwallStore.read(context)
        val image = File(context.filesDir, YearwallEngine.IMAGE_FILE)
        val today = LocalDate.now()
        val items = mutableListOf<StatusItem>()

        // --- Core render: was today's wallpaper actually applied? ------------------------------
        items += if (image.exists() && prefs.lastAppliedEpochDay > 0) {
            val applied = LocalDate.ofEpochDay(prefs.lastAppliedEpochDay)
            StatusItem(
                "Core generator",
                applied == today,
                "Last applied $applied."
            )
        } else if (!prefs.enabled) {
            StatusItem("Core generator", false, "Removed. Nothing will update until Apply.")
        } else {
            StatusItem("Core generator", false, "No wallpaper generated yet.")
        }

        // --- Screen target -----------------------------------------------------------------
        items += StatusItem("Target screens", true, targetLabel(prefs.target))

        val enabled = prefs.enabled
        // --- Cron ------------------------------------------------------------------------------
        val midnightStatus = run {
            val info = try {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(Scheduler.UNIQUE_MIDNIGHT).get().firstOrNull()
            } catch (e: Exception) {
                null
            }
            when {
                !enabled -> StatusItem("Midnight automation", false, "Disabled until Apply.")
                info == null -> StatusItem("Midnight automation", false, "Not scheduled.")
                info.state == WorkInfo.State.ENQUEUED && info.nextScheduleTimeMillis > 0 ->
                    StatusItem(
                        "Midnight automation", true,
                        "Next update ${DATE_FORMAT.format(Date(info.nextScheduleTimeMillis))}."
                    )
                info.state == WorkInfo.State.RUNNING ->
                    StatusItem("Midnight automation", true, "Running right now.")
                else ->
                    StatusItem("Midnight automation", false, "Not scheduled right now.")
            }
        }
        items += midnightStatus

        // --- Midnight precision: the exact alarm (the piece that makes it happen when the
        // phone is locked and asleep) --------------------------------------------
        val exactPermitted = Scheduler.exactAlarmsPermitted(context)
        items += StatusItem(
            "Midnight precision",
            enabled && exactPermitted,
            when {
                !enabled -> "Disabled until Apply."
                exactPermitted -> "Exact alarm set — updates even while locked and asleep."
                else -> "Exact timing needs the 'Alarms & reminders' permission — updates may arrive late."
            }
        )

        // --- Boot script equivalent --------------------------------------------------------------
        items += StatusItem(
            "Boot persistence",
            enabled,
            if (enabled) "Updates on boot, clock and timezone changes." else "Disabled until Apply."
        )

        // --- profile.d session catch-up equivalent -----------------------------------------------
        items += StatusItem(
            "Session catch-up",
            enabled,
            if (enabled) "Updates whenever the app opens." else "Disabled until Apply."
        )

        // --- Wallpaper permission ---------------------------------------------------------------
        val supported = wallpaperManager.isWallpaperSupported
        val permitted = wallpaperManager.isSetWallpaperAllowed
        items += if (supported && permitted) {
            StatusItem("Wallpaper permission", true, "Wallpaper changes allowed.")
        } else {
            StatusItem(
                "Wallpaper permission", false,
                "This device or launcher blocks wallpaper changes."
            )
        }

        // --- Last error (printfc-red-equivalent surfaced into status) ---------------------------
        prefs.lastError?.takeIf { it.isNotBlank() }?.let {
            items += StatusItem("Last error", false, it)
        }

        // Last app crash, full stack trace — the self-serve replacement for adb logcat.
        CrashGuard.lastCrash(context)?.let { trace ->
            items += StatusItem("Last crash", false, trace.take(700))
        }

        items
    }

    private fun targetLabel(target: String): String = when (target) {
        "home" -> "Home screen only."
        "both" -> "Home and lock screens."
        else -> "Lock screen only."
    }
}
