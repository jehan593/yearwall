package com.yearwall.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yearwall.app.wallpaper.WallpaperGenerator
import kotlinx.coroutines.flow.first

// The state the shell system kept in files next to its cron job, ported to DataStore: the
// last-applied day (staleness check), the two design knobs, the pre-yearwall wallpaper
// archive state, and the last error shown in the status list.
private val Context.yearwallDataStore by preferencesDataStore(name = "yearwall")

object YearwallStore {

    data class Prefs(
        // Epoch-day (LocalDate.toEpochDay) of the last successful apply — 0 means never.
        val lastAppliedEpochDay: Long = 0L,
        // Which screens the render is written to: "lock" | "home" | "both".
        // Default "lock" is what termux-wallpaper -l actually did (FLAG_LOCK only — the
        // termux-api source picks exactly one flag: lockscreen ? FLAG_LOCK : FLAG_SYSTEM).
        // "both" is the only behavior with no shell equivalent.
        val target: String = "lock",
        // Design knobs — the only two exposed: grid columns (width/density) and the whole-
        // content Y anchor as a percent of render height (29 ≈ the script's 700/2400).
        val columns: Int = 21,
        val contentYPercent: Int = 29,
        val lastError: String? = null,
        // Screens actually holding the yearwall render after the last apply. A target change
        // resets only the screens that drop out; a screen yearwall never wrote is never touched.
        val homeHasYearwall: Boolean = false,
        val lockHasYearwall: Boolean = false,
        // False after Remove/full reset. Automation and catch-up must never reapply until the
        // user explicitly Applies again. Existing installs with applied state default to true.
        val enabled: Boolean = false,
    )

    private val LAST_APPLIED_EPOCH_DAY = longPreferencesKey("last_applied_epoch_day")
    private val TARGET = stringPreferencesKey("target")
    private val COLUMNS = intPreferencesKey("columns")
    private val CONTENT_Y_PERCENT = intPreferencesKey("content_y_percent")
    private val LAST_ERROR = stringPreferencesKey("last_error")
    private val HOME_HAS_YEARWALL = booleanPreferencesKey("home_has_yearwall")
    private val LOCK_HAS_YEARWALL = booleanPreferencesKey("lock_has_yearwall")
    private val ENABLED = booleanPreferencesKey("enabled")

    suspend fun read(context: Context): Prefs =
        context.yearwallDataStore.data.first().let { p ->
            val homeHas = p[HOME_HAS_YEARWALL] ?: false
            val lockHas = p[LOCK_HAS_YEARWALL] ?: false
            val lastApplied = p[LAST_APPLIED_EPOCH_DAY] ?: 0L
            // Default keeps existing installs active; fresh/cleared installs are inactive until Apply.
            val enabled = p[ENABLED] ?: (homeHas || lockHas || lastApplied != 0L)
            Prefs(
                lastAppliedEpochDay = lastApplied,
                target = p[TARGET] ?: "lock",
                columns = p[COLUMNS] ?: 21,
                contentYPercent = p[CONTENT_Y_PERCENT] ?: 29,
                lastError = p[LAST_ERROR],
                homeHasYearwall = homeHas,
                lockHasYearwall = lockHas,
                enabled = enabled,
            )
        }

    suspend fun setColumns(context: Context, value: Int) =
        context.yearwallDataStore.edit { it[COLUMNS] = value }

    suspend fun setContentYPercent(context: Context, value: Int) =
        context.yearwallDataStore.edit { it[CONTENT_Y_PERCENT] = value }

    // The card's reset: everything back to what the shell script shipped with.
    suspend fun resetAutomation(context: Context) =
        context.yearwallDataStore.edit {
            it[COLUMNS] = 21
            it[CONTENT_Y_PERCENT] = 29
            it[TARGET] = "lock"
        }

    suspend fun setLastAppliedDay(context: Context, epochDay: Long) =
        context.yearwallDataStore.edit {
            it[LAST_APPLIED_EPOCH_DAY] = epochDay
            it[LAST_ERROR] = ""
            it[ENABLED] = true
        }

    suspend fun setEnabled(context: Context, enabled: Boolean) =
        context.yearwallDataStore.edit { it[ENABLED] = enabled }

    suspend fun setLastError(context: Context, message: String?) =
        context.yearwallDataStore.edit { it[LAST_ERROR] = message ?: "" }

    suspend fun setTarget(context: Context, value: String) =
        context.yearwallDataStore.edit { it[TARGET] = value }

    // Mirrors what the last apply actually wrote, including partial failures. Kept across
    // resets so a pending screen reset is never lost just because knobs were reset.
    suspend fun setAppliedScreens(context: Context, home: Boolean, lock: Boolean) =
        context.yearwallDataStore.edit {
            it[HOME_HAS_YEARWALL] = home
            it[LOCK_HAS_YEARWALL] = lock
        }

    // Full teardown — `rm` resets everything, including the design knobs, back to script
    // defaults so a re-setup starts from a known state.
    suspend fun clear(context: Context) =
        context.yearwallDataStore.edit { it.clear() }
}
