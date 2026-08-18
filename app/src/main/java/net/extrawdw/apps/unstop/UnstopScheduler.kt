package net.extrawdw.apps.unstop

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object UnstopScheduler {
    const val ACTION_PERIODIC_UNSTOP = "net.extrawdw.apps.unstop.ACTION_PERIODIC_UNSTOP"
    private const val REQUEST_CODE = 0x554E

    /** Restores a missing alarm without postponing an already-scheduled periodic check. */
    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        if (!UnstopStore.periodicEnabled(appContext)) {
            cancel(appContext)
            return
        }
        if (existingPendingIntent(appContext) != null) {
            PersistentLog.debug(
                appContext,
                "Scheduler",
                "Periodic alarm is already registered; keeping its current trigger time",
            )
            return
        }
        PersistentLog.info(appContext, "Scheduler", "Periodic alarm is missing; creating it")
        schedule(appContext)
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            PersistentLog.error(appContext, "Scheduler", "AlarmManager is unavailable")
            return
        }
        if (!UnstopStore.periodicEnabled(appContext)) {
            PersistentLog.info(appContext, "Scheduler", "Periodic unstop is disabled; cancelling alarm")
            cancel(appContext)
            return
        }

        val intervalMillis = UnstopStore.intervalMinutes(appContext) * 60_000L
        val triggerAt = SystemClock.elapsedRealtime() + intervalMillis
        val firstRunWallClock = Instant.ofEpochMilli(System.currentTimeMillis() + intervalMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            intervalMillis,
            pendingIntent(appContext),
        )
        PersistentLog.info(
            appContext,
            "Scheduler",
            "Scheduled inexact repeating ELAPSED_REALTIME_WAKEUP alarm; " +
                "intervalMinutes=${intervalMillis / 60_000L}, " +
                "firstRunElapsedRealtime=$triggerAt, " +
                "firstRunWallClockApprox=$firstRunWallClock",
        )
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val pendingIntent = existingPendingIntent(appContext)
        if (pendingIntent == null) {
            PersistentLog.debug(appContext, "Scheduler", "No periodic alarm is registered")
            return
        }
        appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
        PersistentLog.info(appContext, "Scheduler", "Cancelled periodic alarm")
    }

    private fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, UnstopAlarmReceiver::class.java).setAction(ACTION_PERIODIC_UNSTOP),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, UnstopAlarmReceiver::class.java).setAction(ACTION_PERIODIC_UNSTOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
