package net.extrawdw.apps.unstop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class UnstopLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                PersistentLog.info(appContext, "Receiver", "Received BOOT_COMPLETED")
                UnstopWorkScheduler.ensureScheduled(
                    appContext,
                    source = "boot_completed",
                    forceNetworkRegistration = true,
                )
                UnstopWorkScheduler.enqueueEvent(appContext, UnstopTrigger.BOOT)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PersistentLog.info(appContext, "Receiver", "Received MY_PACKAGE_REPLACED")
                UnstopWorkScheduler.ensureScheduled(
                    appContext,
                    source = "package_replaced",
                    forceNetworkRegistration = true,
                )
                UnstopWorkScheduler.enqueueEvent(appContext, UnstopTrigger.PACKAGE_REPLACED)
            }

            UnstopNetworkMonitor.ACTION_NETWORK_AVAILABLE -> {
                PersistentLog.info(appContext, "Receiver", "Network became available")
                UnstopWorkScheduler.enqueueEvent(appContext, UnstopTrigger.NETWORK_AVAILABLE)
            }
        }
    }
}
