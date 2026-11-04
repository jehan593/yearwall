package com.yearwall.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.yearwall.app.R
import com.yearwall.app.data.YearwallStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `termux-wallpaper -f "$OUTPUT" -l` equivalent, written per target screen. Faithful to the
 * actual termux-api behavior: `-l` sets FLAG_LOCK only, so lock is the default target, with
 * home/both selectable. Untargeted screens are never touched.
 *
 * Several low-end OEM frameworks (HiOS/XOS-era Android 10 devices) strip the newer
 * WallpaperManager overloads; NoSuchMethodError is an Error, not an Exception, so every
 * fragile call here is Throwable-safe and falls back to the oldest overload instead of
 * crashing.
 */
object WallpaperApplier {

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
                listOf("This device/launcher reports wallpaper support disabled.")
            )
        }
        if (!wallpaperManager.isSetWallpaperAllowed) {
            return@withContext ApplyOutcome(
                false, false,
                listOf("The current launcher refuses wallpaper changes.")
            )
        }

        // Full-frame crop: setBitmap with a null crop lets the system pick a scale, which on
        // some skins overscans oddly. An explicit rect over the whole bitmap pins the frame.
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
    ): Pair<Boolean, String?> {
        val screen = if (which == WallpaperManager.FLAG_SYSTEM) "Home screen" else "Lock screen"

        val full = runCatching { wallpaperManager.setBitmap(bitmap, crop, true, which) }
        if (full.isSuccess) return true to "$screen: wallpaper set."
        val fullFailure = full.exceptionOrNull()
        if (fullFailure != null &&
            which == WallpaperManager.FLAG_SYSTEM &&
            runCatching { wallpaperManager.setBitmap(bitmap) }.isSuccess
        ) {
            return true to "$screen: wallpaper set."
        }
        return false to "$screen: ${fullFailure?.javaClass?.simpleName} ${fullFailure?.message ?: ""}".trim()
    }

    /**
     * Removal: set the same default wallpaper the shell script's `rm` restored — a bundled
     * copy of `termux-config/data/wallpaper/wallpaper.png` (res/raw/default_wallpaper.png) —
     * onto every screen yearwall touched. No reading, no archiving, no restore: the default
     * write is unconditional and Throwable-safe.
     */
    suspend fun restoreDefaults(context: Context): List<String> = withContext(Dispatchers.IO) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val src = runCatching { android.graphics.BitmapFactory.decodeResource(context.resources, R.raw.default_wallpaper) }
        val bitmap = src.getOrNull()
        if (bitmap == null) {
            return@withContext listOf("Remove: bundled default wallpaper unreadable (${src.exceptionOrNull()?.javaClass?.simpleName}).")
        }
        val crop = Rect(0, 0, bitmap.width, bitmap.height)
        val messages = mutableListOf<String>()
        for (flag in flagsFor(YearwallStore.read(context).target)) {
            messages += setDefaultOne(wallpaperManager, bitmap, crop, flag)
        }
        messages
    }

    private fun setDefaultOne(
        wallpaperManager: WallpaperManager,
        bitmap: Bitmap,
        crop: Rect,
        which: Int
    ): String {
        val screen = if (which == WallpaperManager.FLAG_SYSTEM) "Home screen" else "Lock screen"
        val full = runCatching { wallpaperManager.setBitmap(bitmap, crop, true, which) }
        if (full.isSuccess) return "$screen: default wallpaper set."
        val fullFailure = full.exceptionOrNull()
        if (fullFailure != null &&
            which == WallpaperManager.FLAG_SYSTEM &&
            runCatching { wallpaperManager.setBitmap(bitmap) }.isSuccess
        ) {
            return "$screen: default wallpaper set."
        }
        return "$screen: default-wallpaper write failed — " +
            "${fullFailure?.javaClass?.simpleName} ${fullFailure?.message ?: ""}".trim()
    }
}
