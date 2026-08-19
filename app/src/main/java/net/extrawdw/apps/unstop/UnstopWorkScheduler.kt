package net.extrawdw.apps.unstop

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object UnstopWorkScheduler {
    private const val PERIODIC_WORK_NAME = "net.extrawdw.apps.unstop.periodic"
    private const val EVENT_WORK_NAME = "net.extrawdw.apps.unstop.event"
    private const val PERIODIC_WORK_TAG = "periodic-unstop"
    private const val EVENT_WORK_TAG = "event-unstop"
    private const val FLEX_MINUTES = 5L

    /** Repairs missing persisted work without changing an existing schedule. */
    fun ensureScheduled(
        context: Context,
        source: String,
        forceNetworkRegistration: Boolean = false,
    ) {
        schedule(
            context = context,
            source = source,
            policy = ExistingPeriodicWorkPolicy.KEEP,
            forceNetworkRegistration = forceNetworkRegistration,
        )
    }

    /** Applies changed settings to the existing unique periodic work. */
    fun updateScheduled(context: Context, source: String) {
        schedule(
            context = context,
            source = source,
            policy = ExistingPeriodicWorkPolicy.UPDATE,
            forceNetworkRegistration = false,
        )
    }

    fun enqueueEvent(context: Context, trigger: UnstopTrigger) {
        val appContext = context.applicationContext
        if (!UnstopStore.periodicEnabled(appContext)) {
            PersistentLog.info(
                appContext,
                "Scheduler",
                "Ignoring ${trigger.logName} event because periodic unstop is disabled",
            )
            return
        }

        val request = OneTimeWorkRequestBuilder<UnstopWorker>()
            .setInputData(workDataOf(UnstopWorker.KEY_TRIGGER to trigger.name))
            .addTag(EVENT_WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            EVENT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        PersistentLog.info(
            appContext,
            "Scheduler",
            "Requested unique ${trigger.logName} work; candidateId=${request.id}",
        )
    }

    private fun schedule(
        context: Context,
        source: String,
        policy: ExistingPeriodicWorkPolicy,
        forceNetworkRegistration: Boolean,
    ) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        if (!UnstopStore.periodicEnabled(appContext)) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(EVENT_WORK_NAME)
            UnstopNetworkMonitor.unregister(appContext)
            PersistentLog.info(
                appContext,
                "Scheduler",
                "Requested cancellation of background work; source=$source",
            )
            return
        }

        val intervalMinutes = UnstopStore.intervalMinutes(appContext).toLong()
        val request = PeriodicWorkRequestBuilder<UnstopWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
            FLEX_MINUTES,
            TimeUnit.MINUTES,
        )
            .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
            .addTag(PERIODIC_WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, policy, request)
        UnstopNetworkMonitor.ensureRegistered(appContext, forceNetworkRegistration)
        PersistentLog.info(
            appContext,
            "Scheduler",
            "Requested unique periodic work reconciliation; source=$source, policy=$policy, " +
                "intervalMinutes=$intervalMinutes, flexMinutes=$FLEX_MINUTES, " +
                "candidateId=${request.id}",
        )
    }
}

/**
 * Uses a PendingIntent callback so network availability can wake the private receiver without
 * keeping this process or a service resident.
 */
internal object UnstopNetworkMonitor {
    const val ACTION_NETWORK_AVAILABLE =
        "net.extrawdw.apps.unstop.ACTION_NETWORK_AVAILABLE"

    private const val REQUEST_CODE = 0x4E45
    private const val PREFS = "network_monitor_state"
    private const val KEY_REGISTRATION_GENERATION = "registration_generation"

    fun ensureRegistered(context: Context, force: Boolean) {
        val appContext = context.applicationContext
        val generation = registrationGeneration(appContext)
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && preferences.getString(KEY_REGISTRATION_GENERATION, null) == generation) {
            PersistentLog.debug(
                appContext,
                "Network",
                "Network callback is already registered for generation=$generation",
            )
            return
        }

        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            PersistentLog.error(appContext, "Network", "ConnectivityManager is unavailable")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            // Registering the same PendingIntent replaces the old callback instead of duplicating it.
            connectivityManager.registerNetworkCallback(request, callbackPendingIntent(appContext))
            preferences.edit { putString(KEY_REGISTRATION_GENERATION, generation) }
            PersistentLog.info(
                appContext,
                "Network",
                "Registered persistent network-available callback; generation=$generation",
            )
        } catch (error: RuntimeException) {
            PersistentLog.error(
                appContext,
                "Network",
                "Could not register network-available callback",
                error,
            )
        }
    }

    fun unregister(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingIntent = callbackPendingIntent(appContext)
        try {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(pendingIntent)
            PersistentLog.info(appContext, "Network", "Unregistered network callback")
        } catch (_: IllegalArgumentException) {
            PersistentLog.debug(appContext, "Network", "No network callback was registered")
        } catch (error: RuntimeException) {
            PersistentLog.error(appContext, "Network", "Could not unregister network callback", error)
        } finally {
            pendingIntent.cancel()
            preferences.edit { remove(KEY_REGISTRATION_GENERATION) }
        }
    }

    private fun callbackPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, UnstopLifecycleReceiver::class.java)
            .setAction(ACTION_NETWORK_AVAILABLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun registrationGeneration(context: Context): String {
        val bootCount = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            -1,
        )
        return "$bootCount:${BuildConfig.VERSION_CODE}"
    }
}
