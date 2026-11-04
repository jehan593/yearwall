package com.yearwall.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.yearwall.app.R
import com.yearwall.app.data.YearwallStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * `termux-wallpaper -f "$OUTPUT" -l` equivalent. Faithful to the actual termux-api behavior:
 * `-l` writes FLAG_LOCK only, so lock is the default target here, with home/both selectable.
 * Untargeted screens are never touched. Also owns the save-before-apply / restore-on-remove
 * bookkeeping.
 */
object WallpaperApplier {

    private const val ORIGINAL_HOME = "original_home.png"
    private const val ORIGINAL_LOCK = "original_lock.png"

    // "home" | "lock" | "both" → the WallpaperManager flags that will actually be written.
    fun flagsFor(target: String): List<Int> = when (target) {
        "home" -> listOf(WallpaperManager.FLAG_SYSTEM)
        "both" -> listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)
        else -> listOf(WallpaperManager.FLAG_LOCK)
    }

    suspend fun apply(context: Context, generated: Bitmap, target: String): ApplyOutcome = withContext(Dispatchers.IO) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (!wallpaperManager.isWallpaperSupported) {
            return@withContext ApplyOutcome(
                false, false,
                listOf("This device/launcher reports wallpaper support disabled (isWallpaperSupported = false).")
            )
        }
        if (!wallpaperManager.isSetWallpaperAllowed) {
            return@withContext ApplyOutcome(
                false, false,
                listOf("The current launcher refuses wallpaper changes (isSetWallpaperAllowed = false).")
            )
        }

        backupOriginals(context, wallpaperManager, target)
        // Full-frame crop: setBitmap with a null crop lets the system pick a scale, which on
        // some skins overscans oddly. An explicit rect over the whole bitmap pins the frame
        // the Canvas drew.
        val crop = Rect(0, 0, generated.width, generated.height)
        val flags = flagsFor(target)
        var homeOk = true
        var homeMsg: String? = null
        var lockOk = true
        var lockMsg: String? = null
        for (flag in flags) {
            val (ok, msg) = setOne(wallpaperManager, generated, crop, flag)
            if (flag == WallpaperManager.FLAG_SYSTEM) {
                homeOk = ok; homeMsg = msg
            } else {
                lockOk = ok; lockMsg = msg
            }
        }
        // Untargeted screens are untouched, and untouched counts as "no failure" — but note it.
        if (!flags.contains(WallpaperManager.FLAG_SYSTEM)) {
            homeMsg = "Home screen: untouched (target = $target)."
        }
        if (!flags.contains(WallpaperManager.FLAG_LOCK)) {
            lockMsg = "Lock screen: untouched (target = $target)."
        }
        ApplyOutcome(homeOk, lockOk, listOfNotNull(homeMsg, lockMsg))
    }

    private fun setOne(
        wallpaperManager: WallpaperManager,
        bitmap: Bitmap,
        crop: Rect,
        which: Int
    ): Pair<Boolean, String?> = try {
        wallpaperManager.setBitmap(bitmap, crop, true, which)
        true to null
    } catch (t: Throwable) {
        val screen = if (which == WallpaperManager.FLAG_SYSTEM) "Home screen" else "Lock screen"
        false to "$screen: ${t.message ?: t.javaClass.simpleName}"
    }

    /*
     * Snapshot the current wallpaper once per targeted screen, before yearwall first touches
     * it — Remove restores that. A wall yearwall itself painted (recognized by the render's
     * marker pixels) is never saved as a "previous" wallpaper, whichever day's render it is.
     * Anything unreadable is skipped, retried next apply, and explained in the status row.
     */
    private suspend fun backupOriginals(
        context: Context,
        wallpaperManager: WallpaperManager,
        target: String
    ) {
        val prefs = YearwallStore.read(context)
        // Explicit: only archive screens this run is about to take over — a lock-only install
        // must never snapshot (or later "restore") the home screen.
        val targetingHome = flagsFor(target).contains(WallpaperManager.FLAG_SYSTEM)
        val targetingLock = flagsFor(target).contains(WallpaperManager.FLAG_LOCK)
        val currentHome = if (targetingHome) currentBitmap(wallpaperManager, WallpaperManager.FLAG_SYSTEM) else null
        val currentLock = if (targetingLock) currentBitmap(wallpaperManager, WallpaperManager.FLAG_LOCK) else null

        // The status row's only real "why" — every path below speaks here, because a silently
        // skipped archive is indistinguishable from "backup broken" to the person using it.
        val notes = mutableListOf<String>()

        val saveHome = targetingHome &&
            !prefs.originalsSavedHome &&
            currentHome != null &&
            !WallpaperGenerator.isAppRender(currentHome)
        val saveLock = targetingLock &&
            !prefs.originalsSavedLock &&
            currentLock != null &&
            !WallpaperGenerator.isAppRender(currentLock)

        if (saveHome) save(context, ORIGINAL_HOME, currentHome)
        if (saveLock) save(context, ORIGINAL_LOCK, currentLock)

        if (targetingHome) {
            notes += when {
                saveHome -> "Home: previous wallpaper saved."
                currentHome == null ->
                    "Home: nothing readable to save yet — will retry."
                WallpaperGenerator.isAppRender(currentHome) ->
                    "Home: wall was set by yearwall — nothing to save."
                else -> "Home: already saved."
            }
        }
        if (targetingLock) {
            notes += when {
                saveLock -> "Lock: previous wallpaper saved."
                currentLock == null ->
                    "Lock: nothing readable to save yet — will retry."
                WallpaperGenerator.isAppRender(currentLock) ->
                    "Lock: wall was set by yearwall — nothing to save."
                else -> "Lock: already saved."
            }
        }
        YearwallStore.setBackupNote(context, notes.joinToString(" · "))

        // Flip "handled" only when something conclusive happened for that screen: a successful
        // archive, or an app-made wall (no user wallpaper exists to preserve — and a later
        // apply would reconsider, but the note already explains the situation).
        val newHome = prefs.originalsSavedHome || saveHome ||
            (targetingHome && currentHome != null && WallpaperGenerator.isAppRender(currentHome))
        val newLock = prefs.originalsSavedLock || saveLock ||
            (targetingLock && currentLock != null && WallpaperGenerator.isAppRender(currentLock))
        if (newHome != prefs.originalsSavedHome || newLock != prefs.originalsSavedLock) {
            YearwallStore.setOriginalsSaved(context, newHome, newLock)
        }
    }

    private suspend fun save(context: Context, name: String, bitmap: Bitmap) {
        FileOutputStream(File(context.filesDir, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * `rm`'s wallpaper restore. Each screen restores independently and only its own snapshot
     * file is deleted on success — a half-failed restore stays retryable for the next attempt
     * upstream. On a targeted screen with NO snapshot, the termux script's own behavior kicks
     * in: it set the fixed default (`termux-config/data/wallpaper/wallpaper.png`, now bundled
     * as res/raw/default_wallpaper.png) to the lock screen — a targeted screen can't be left
     * showing a yearwall render just because the archive never happened.
     */
    suspend fun removeAndRestore(context: Context): List<String> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<String>()
        val wallpaperManager = WallpaperManager.getInstance(context)
        val targeted = flagsFor(YearwallStore.read(context).target)
        val lockTargeted = targeted.contains(WallpaperManager.FLAG_LOCK)
        val homeTargeted = targeted.contains(WallpaperManager.FLAG_SYSTEM)

        val home = File(context.filesDir, ORIGINAL_HOME)
        if (home.exists()) {
            restoreOne(wallpaperManager, home, WallpaperManager.FLAG_SYSTEM, "Home screen")
                ?.let { messages += it }
        } else if (YearwallStore.read(context).originalsSavedHome) {
            messages += "Home screen: original backup was saved but the file is missing."
        } else if (homeTargeted) {
            setDefault(wallpaperManager, context, WallpaperManager.FLAG_SYSTEM, "Home screen")
                ?.let { messages += it }
        }

        val lock = File(context.filesDir, ORIGINAL_LOCK)
        if (lock.exists()) {
            restoreOne(wallpaperManager, lock, WallpaperManager.FLAG_LOCK, "Lock screen")
                ?.let { messages += it }
        } else if (YearwallStore.read(context).originalsSavedLock) {
            messages += "Lock screen: original backup was saved but the file is missing."
        } else if (lockTargeted) {
            setDefault(wallpaperManager, context, WallpaperManager.FLAG_LOCK, "Lock screen")
                ?.let { messages += it }
        }

        messages
    }

    private fun setDefault(
        wallpaperManager: WallpaperManager,
        context: Context,
        which: Int,
        screen: String
    ): String? = try {
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            context.resources, R.raw.default_wallpaper
        ) ?: throw IllegalStateException("bundled default unreadable")
        val crop = Rect(0, 0, bitmap.width, bitmap.height)
        wallpaperManager.setBitmap(bitmap, crop, true, which)
        "$screen: termux default wallpaper set (no original backup existed)."
    } catch (t: Throwable) {
        "$screen: default-wallpaper fallback failed — ${t.message ?: t.javaClass.simpleName}"
    }

    private fun restoreOne(
        wallpaperManager: WallpaperManager,
        backup: File,
        which: Int,
        screen: String
    ): String? = try {
        val bitmap = android.graphics.BitmapFactory.decodeFile(backup.absolutePath)
            ?: throw IllegalStateException("backup unreadable")
        val crop = Rect(0, 0, bitmap.width, bitmap.height)
        wallpaperManager.setBitmap(bitmap, crop, true, which)
        backup.delete()
        "$screen: original wallpaper restored."
    } catch (t: Throwable) {
        "$screen: restore failed — ${t.message ?: t.javaClass.simpleName}"
    }

    private fun currentBitmap(wallpaperManager: WallpaperManager, which: Int): Bitmap? {
        return try {
            // Two attempts: getDrawable first, peekDrawable as the fallback — some skins
            // return null/throw from one while the other still resolves the live wallpaper.
            var drawable: Drawable? = try {
                wallpaperManager.getDrawable(which)
            } catch (e: Exception) {
                null
            }
            if (drawable == null) {
                drawable = try { wallpaperManager.peekDrawable(which) } catch (e: Exception) { null }
            }
            val bitmap: Bitmap? = when {
                drawable is BitmapDrawable -> drawable.bitmap
                drawable != null -> {
                    // No bitmap payload (some skins hand back a scaled drawable) — rasterize
                    // onto the system's desired-size canvas instead.
                    val desiredWidth = wallpaperManager.desiredMinimumWidth.coerceAtLeast(1)
                    val desiredHeight = wallpaperManager.desiredMinimumHeight.coerceAtLeast(1)
                    Bitmap.createBitmap(desiredWidth, desiredHeight, Bitmap.Config.ARGB_8888)
                        .also { drawable.draw(Canvas(it)) }
                }
                else -> null
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
