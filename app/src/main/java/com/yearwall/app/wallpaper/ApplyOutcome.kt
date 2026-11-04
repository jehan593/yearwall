package com.yearwall.app.wallpaper

/**
 * Per-screen result of one apply pass. The split mirrors the shell script's `|| exit 1`
 * granularity — some skins allow the home screen but throw on FLAG_LOCK, and treating that
 * as a total failure would stamp "never applied" onto a wallpaper that's visibly in place.
 */
data class ApplyOutcome(
    val homeOk: Boolean,
    val lockOk: Boolean,
    val messages: List<String>
) {
    val anyOk: Boolean get() = homeOk || lockOk
    val allOk: Boolean get() = homeOk && lockOk
}
