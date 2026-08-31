package net.extrawdw.apps.unstop

import android.content.Context

internal enum class UnstopTrigger {
    MANUAL,
    PERIODIC,
    BOOT,
    PACKAGE_REPLACED,
    NETWORK_AVAILABLE;

    val logName: String
        get() = name.lowercase()

    companion object {
        fun fromWorkerInput(value: String?): UnstopTrigger =
            entries.firstOrNull { it.name == value } ?: PERIODIC
    }
}

internal data class UnstopSummary(
    val attempted: Int,
    val succeeded: Boolean,
    val status: ShizukuStatus,
    val message: String,
)

internal object UnstopEngine {
    @Synchronized
    fun runAndRecord(
        context: Context,
        trigger: UnstopTrigger = UnstopTrigger.MANUAL,
    ): UnstopSummary = runAndRecordLocked(context, trigger)

    @Synchronized
    fun runIfLastCheckIsOlderThan(
        context: Context,
        trigger: UnstopTrigger,
        minimumAgeMillis: Long,
    ): UnstopSummary? {
        val appContext = context.applicationContext
        val lastRunAt = UnstopStore.lastRun(appContext).timestamp
        val ageMillis = System.currentTimeMillis() - lastRunAt
        if (lastRunAt > 0L && ageMillis in 0 until minimumAgeMillis) {
            PersistentLog.info(
                appContext,
                "Engine",
                "Skipping ${trigger.logName} check; last check completed ${ageMillis} ms ago",
            )
            return null
        }
        return runAndRecordLocked(appContext, trigger)
    }

    private fun runAndRecordLocked(
        context: Context,
        trigger: UnstopTrigger,
    ): UnstopSummary {
        val appContext = context.applicationContext
        val users = UnstopStore.monitorUsers(appContext)
        val enabledPackages = UnstopStore.enabledAppPackages(appContext)
        val startedAt = System.currentTimeMillis()
        PersistentLog.info(
            appContext,
            "Engine",
            "Starting ${trigger.logName} check; users=${users.sorted()}, " +
                "selectedPackageCount=${enabledPackages.size}, packages=${enabledPackages.sorted()}",
        )
        // Background work intentionally does not enumerate every FCM receiver. The UserService
        // checks only these selected package names for each monitored user, then unstops matches.
        val result = ShizukuController.unstopSelected(
            appContext,
            users,
            enabledPackages,
            trigger.logName,
        )
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
            "Finished ${trigger.logName} check in ${System.currentTimeMillis() - startedAt} ms; " +
                "status=${result.status}, commandSucceeded=${result.commandSucceeded}, " +
                "unstopped=${result.attempted}, detail=${result.detail ?: "none"}, summary=$message",
        )
        return summary
    }
}
