package net.extrawdw.apps.unstop

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class LastRunState(
    val timestamp: Long,
    val summary: String,
)

/** Small, synchronous preference store shared by the UI and background workers. */
internal object UnstopStore {
    val INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 2 * 60, 3 * 60)
    private const val DEFAULT_INTERVAL_MINUTES = 30
    private const val PREFS = "unstop_preferences"
    private const val KEY_USERS = "monitor_users"
    private const val KEY_APPS = "enabled_apps"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_PERIODIC_ENABLED = "periodic_enabled"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LAST_RUN_SUMMARY = "last_run_summary"
    private const val APP_SEPARATOR = "|"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun monitorUsers(context: Context): Set<Int> {
        val raw = prefs(context).getString(KEY_USERS, null) ?: return setOf(0)
        return raw.split(',')
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    fun setMonitorUser(context: Context, userId: Int, enabled: Boolean) {
        val users = monitorUsers(context).toMutableSet().apply {
            if (enabled) add(userId) else remove(userId)
        }
        prefs(context).edit { putString(KEY_USERS, users.sorted().joinToString(",")) }
        PersistentLog.info(
            context,
            "Settings",
            "Android user $userId ${if (enabled) "enabled" else "disabled"}; monitoredUsers=${users.sorted()}",
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun isAppEnabled(context: Context, userId: Int, packageName: String): Boolean =
        packageName in enabledAppPackages(context)

    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val apps = enabledAppPackages(context).toMutableSet().apply {
            if (enabled) add(packageName) else remove(packageName)
        }
        prefs(context).edit { putStringSet(KEY_APPS, apps) }
        PersistentLog.info(
            context,
            "Settings",
            "Package $packageName ${if (enabled) "enabled" else "disabled"}; selectedPackageCount=${apps.size}",
        )
    }

    /** Package selection is intentionally independent of Android user/profile. */
    fun enabledAppPackages(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_APPS, emptySet()).orEmpty().toSet()
        val normalized = stored.mapNotNull(::packageNameFromStoredValue).toSet()
        if (normalized != stored) {
            prefs(context).edit { putStringSet(KEY_APPS, normalized) }
        }
        return normalized
    }

    fun intervalMinutes(context: Context): Int {
        val stored = prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        return stored.takeIf { it in INTERVAL_OPTIONS_MINUTES } ?: DEFAULT_INTERVAL_MINUTES
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        val normalized = minutes.takeIf { it in INTERVAL_OPTIONS_MINUTES }
            ?: DEFAULT_INTERVAL_MINUTES
        prefs(context).edit { putInt(KEY_INTERVAL_MINUTES, normalized) }
        PersistentLog.info(context, "Settings", "Check interval changed to $normalized minutes")
    }

    fun periodicEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERIODIC_ENABLED, true)

    fun setPeriodicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_PERIODIC_ENABLED, enabled) }
        PersistentLog.info(
            context,
            "Settings",
            "Periodic unstop ${if (enabled) "enabled" else "disabled"}",
        )
    }

    fun lastRun(context: Context): LastRunState = lastRunState(context, prefs(context))

    /** Emits persisted background-run updates and rereads the current value on every collection. */
    fun observeLastRun(context: Context): Flow<LastRunState> {
        val appContext = context.applicationContext
        return callbackFlow {
            val preferences = prefs(appContext)
            fun emitCurrent() {
                trySend(lastRunState(appContext, preferences))
            }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_LAST_RUN_AT || key == KEY_LAST_RUN_SUMMARY) emitCurrent()
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            emitCurrent()
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
    }

    fun saveLastRun(context: Context, timestamp: Long, summary: String) {
        prefs(context).edit {
            putLong(KEY_LAST_RUN_AT, timestamp)
            putString(KEY_LAST_RUN_SUMMARY, summary)
        }
    }

    private fun lastRunState(
        context: Context,
        preferences: SharedPreferences,
    ): LastRunState = LastRunState(
        timestamp = preferences.getLong(KEY_LAST_RUN_AT, 0L),
        summary = preferences.getString(KEY_LAST_RUN_SUMMARY, null)
            ?: context.getString(R.string.last_run_none),
    )

    private fun packageNameFromStoredValue(value: String): String? {
        val separator = value.indexOf(APP_SEPARATOR)
        if (separator > 0) {
            return value.substring(separator + APP_SEPARATOR.length)
                .takeIf(::isPackageName)
        }
        return value.takeIf(::isPackageName)
    }

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))

}
