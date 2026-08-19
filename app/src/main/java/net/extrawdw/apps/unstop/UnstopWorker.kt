package net.extrawdw.apps.unstop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class UnstopWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trigger = UnstopTrigger.fromWorkerInput(inputData.getString(KEY_TRIGGER))
        PersistentLog.info(
            applicationContext,
            "Worker",
            "Starting ${trigger.logName} work; id=$id, attempt=$runAttemptCount",
        )
        try {
            val summary = if (trigger == UnstopTrigger.NETWORK_AVAILABLE) {
                UnstopEngine.runIfLastCheckIsOlderThan(
                    applicationContext,
                    trigger,
                    NETWORK_EVENT_MINIMUM_AGE_MILLIS,
                )
            } else {
                UnstopEngine.runAndRecord(applicationContext, trigger)
            }
            PersistentLog.info(
                applicationContext,
                "Worker",
                "Finished ${trigger.logName} work; id=$id, " +
                    if (summary == null) "result=skipped" else "result=completed",
            )
            Result.success()
        } catch (error: CancellationException) {
            PersistentLog.info(
                applicationContext,
                "Worker",
                "Cancelled ${trigger.logName} work; id=$id",
            )
            throw error
        } catch (error: Throwable) {
            PersistentLog.error(
                applicationContext,
                "Worker",
                "Failed ${trigger.logName} work; id=$id",
                error,
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val NETWORK_EVENT_MINIMUM_AGE_MILLIS = 5 * 60_000L
    }
}
