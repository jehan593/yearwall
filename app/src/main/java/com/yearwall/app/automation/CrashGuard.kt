package com.yearwall.app.automation

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists uncaught exceptions to app storage and shows them in the Status list — the
 * self-serve replacement for `adb logcat`. Delegates to the previous handler so the system
 * dialog still appears.
 */
object CrashGuard {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = StringWriter()
                    .also { throwable.printStackTrace(PrintWriter(it)) }
                    .toString()
                File(appContext.filesDir, FILE_NAME)
                    .writeText("${thread.name} — $throwable\n$stack", Charsets.UTF_8)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrash(context: Context): String? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE_NAME).delete() } catch (_: Throwable) { }
    }
}
