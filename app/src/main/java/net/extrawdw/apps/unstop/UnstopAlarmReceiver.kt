package net.extrawdw.apps.unstop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class UnstopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != UnstopScheduler.ACTION_PERIODIC_UNSTOP &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) return

        val appContext = context.applicationContext
        val trigger = if (action == Intent.ACTION_BOOT_COMPLETED) {
            PersistentLog.info(
                appContext,
                "Receiver",
                "Received BOOT_COMPLETED; restoring alarm and evaluating immediate check",
            )
            UnstopScheduler.schedule(appContext)
            if (!UnstopStore.periodicEnabled(appContext)) {
                PersistentLog.info(
                    appContext,
                    "Receiver",
                    "Periodic unstop is disabled; skipping boot check",
                )
                return
            }
            UnstopTrigger.BOOT
        } else {
            PersistentLog.info(appContext, "Receiver", "Received periodic alarm")
            UnstopTrigger.PERIODIC
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                UnstopEngine.runAndRecord(appContext, trigger)
            } catch (error: Throwable) {
                PersistentLog.error(appContext, "Receiver", "Background unstop crashed", error)
            } finally {
                PersistentLog.debug(appContext, "Receiver", "Background receiver finished")
                pendingResult.finish()
            }
        }
    }
}

internal enum class UnstopTrigger {
    MANUAL,
    PERIODIC,
    BOOT,
}

internal data class UnstopSummary(
    val attempted: Int,
    val succeeded: Boolean,
    val status: ShizukuStatus,
    val message: String,
)

internal object UnstopEngine {
    fun runAndRecord(
        context: Context,
        trigger: UnstopTrigger = UnstopTrigger.MANUAL,
    ): UnstopSummary {
        val appContext = context.applicationContext
        val users = UnstopStore.monitorUsers(appContext)
        val enabledPackages = UnstopStore.enabledAppPackages(appContext)
        val startedAt = System.currentTimeMillis()
        PersistentLog.info(
            appContext,
            "Engine",
            "Starting ${trigger.name.lowercase()} check; users=${users.sorted()}, " +
                "selectedPackageCount=${enabledPackages.size}, packages=${enabledPackages.sorted()}",
        )
        // Periodic work intentionally does not enumerate every FCM receiver. The UserService
        // checks only these selected package names for each monitored user, then unstops matches.
        val result = ShizukuController.unstopSelected(appContext, users, enabledPackages)
        val message = when {
            result.status == ShizukuStatus.NOT_RUNNING ->
                appContext.getString(R.string.summary_shizuku_not_running)
            result.status == ShizukuStatus.PERMISSION_REQUIRED ->
                appContext.getString(R.string.summary_shizuku_permission)
            result.status == ShizukuStatus.ERROR -> result.detail?.let {
                appContext.getString(R.string.summary_shizuku_failed_detail, it)
            } ?: appContext.getString(R.string.summary_shizuku_failed)
            enabledPackages.isEmpty() -> appContext.getString(R.string.summary_no_apps_selected)
            result.commandSucceeded && result.attempted > 0 ->
                appContext.resources.getQuantityString(
                    R.plurals.summary_unstopped,
                    result.attempted,
                    result.attempted,
                )
            result.commandSucceeded -> appContext.getString(R.string.summary_none_stopped)
            else -> appContext.getString(R.string.summary_some_failed)
        }
        val summary = UnstopSummary(
            attempted = result.attempted,
            succeeded = result.commandSucceeded,
            status = result.status,
            message = message,
        )
        UnstopStore.saveLastRun(appContext, System.currentTimeMillis(), message)
        PersistentLog.info(
            appContext,
            "Engine",
            "Finished ${trigger.name.lowercase()} check in ${System.currentTimeMillis() - startedAt} ms; " +
                "status=${result.status}, commandSucceeded=${result.commandSucceeded}, " +
                "unstopped=${result.attempted}, detail=${result.detail ?: "none"}, summary=$message",
        )
        return summary
    }
}
