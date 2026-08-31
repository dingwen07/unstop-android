package net.extrawdw.apps.unstop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class ShizukuStatus {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
    ERROR,
}

internal data class ShizukuRunResult(
    val status: ShizukuStatus,
    val attempted: Int = 0,
    val commandSucceeded: Boolean = false,
    val detail: String? = null,
)

internal data class ShizukuDiscoveryBatch(
    val usersOutput: String,
    val snapshotsByUser: Map<Int, String>,
)

internal object ShizukuController {
    const val PERMISSION_REQUEST_CODE = 0x554E
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SERVICE_CONNECT_TIMEOUT_SECONDS = 15L
    private const val SERVICE_UNBIND_TIMEOUT_SECONDS = 5L
    private const val USER_SERVICE_VERSION = 1
    private val serviceLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unstoppedPattern = Regex("__UNSTOP_UNSTOPPED__(\\d+)")
    private val failedPattern = Regex("__UNSTOP_FAILED__(\\d+)")
    private val successfulUnstopPattern = Regex(
        "__UNSTOP_RESULT__(\\d+)\\|([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)\\|unstopped",
    )
    private val userIdPattern = Regex("UserInfo\\{(\\d+):")

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UnstopUserService::class.java.name),
    )
        .daemon(true)
        .processNameSuffix("unstop")
        .tag("unstop")
        .debuggable(BuildConfig.DEBUG)
        .version(USER_SERVICE_VERSION)

    fun status(): ShizukuStatus = runCatching {
        when {
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.PERMISSION_REQUIRED
            else -> ShizukuStatus.READY
        }
    }.getOrDefault(ShizukuStatus.ERROR)

    fun requestPermission(): Boolean = runCatching {
        when {
            !Shizuku.pingBinder() -> false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
            else -> {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                false
            }
        }
    }.getOrDefault(false)

    fun openManager(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Checks only selected packages for each monitored user, then unstops stopped instances. */
    fun unstopSelected(
        context: Context,
        userIds: Collection<Int>,
        packageNames: Collection<String>,
        trigger: String,
    ): ShizukuRunResult {
        val appContext = context.applicationContext
        val currentStatus = status()
        val packages = packageNames.distinct().filter(::isPackageName).sorted()
        val rejectedPackages = packageNames.distinct().filterNot(::isPackageName).sorted()
        if (rejectedPackages.isNotEmpty()) {
            PersistentLog.warn(
                appContext,
                "Shizuku",
                "Ignored invalid package names: $rejectedPackages",
            )
        }
        if (currentStatus != ShizukuStatus.READY || packages.isEmpty()) {
            PersistentLog.warn(
                appContext,
                "Shizuku",
                "Skipping shell command; status=$currentStatus, validPackageCount=${packages.size}",
            )
            return ShizukuRunResult(currentStatus, 0, commandSucceeded = false)
        }

        val users = userIds.distinct().sorted()
        PersistentLog.info(
            appContext,
            "Shizuku",
            "Running Binder package commands; users=$users, selectedPackageCount=${packages.size}",
        )
        val diagnosticResult = runCatching {
            withService(appContext, "unstop") { service ->
                service.unstop(
                    packages.toTypedArray(),
                    users.toIntArray(),
                    trigger,
                )
            }
        }
        val diagnosticOutput = diagnosticResult.getOrNull()
        if (diagnosticOutput == null) {
            val error = diagnosticResult.exceptionOrNull()
            PersistentLog.error(
                appContext,
                "Shizuku",
                "UserService returned no diagnostic output",
                error,
            )
            return ShizukuRunResult(
                status = ShizukuStatus.ERROR,
                attempted = 0,
                commandSucceeded = false,
                detail = error?.message
                    ?: "UserService returned no diagnostic output",
            )
        }

        PersistentLog.debug(appContext, "Shizuku", "UserService output:\n$diagnosticOutput")
        successfulUnstopPattern.findAll(diagnosticOutput).forEach { match ->
            val userId = match.groupValues[1].toIntOrNull() ?: return@forEach
            PackageActivityLog.recordUnstopped(
                appContext,
                userId = userId,
                packageName = match.groupValues[2],
            )
        }
        val unstopped = unstoppedPattern.find(diagnosticOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val failed = failedPattern.find(diagnosticOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        PersistentLog.info(
            appContext,
            "Shizuku",
            "Binder package commands completed; unstopped=$unstopped, failed=$failed",
        )
        return ShizukuRunResult(
            status = ShizukuStatus.READY,
            attempted = unstopped,
            commandSucceeded = failed == 0,
        )
    }

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))

    /** Lists users and scans every requested user through one retained UserService binding. */
    fun discoverFcmApps(
        context: Context,
        requestedUserIds: Collection<Int>?,
    ): ShizukuDiscoveryBatch? = runCatching {
        val appContext = context.applicationContext
        withService(appContext, "discover-fcm-batch") { service ->
            val usersOutput = service.listUsers()
            val users = requestedUserIds
                ?.distinct()
                ?.sorted()
                ?: userIdPattern.findAll(usersOutput)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .distinct()
                    .sorted()
                    .toList()
                    .ifEmpty { listOf(0) }
            PersistentLog.info(
                appContext,
                "Shizuku",
                "Running one discovery batch; users=$users",
            )
            val snapshots = linkedMapOf<Int, String>()
            users.forEach { userId ->
                snapshots[userId] = service.discoverFcmApps(userId)
            }
            ShizukuDiscoveryBatch(usersOutput, snapshots)
        }
    }.onFailure { error ->
        PersistentLog.error(context, "Shizuku", "Could not complete FCM discovery batch", error)
    }.getOrNull()

    fun reconcileFcmConnectionProtection(context: Context, trigger: String): Boolean {
        val appContext = context.applicationContext
        if (status() != ShizukuStatus.READY) {
            PersistentLog.info(
                appContext,
                "Shizuku",
                "Deferred FCM connection protection reconciliation; Shizuku is not ready",
            )
            return false
        }
        val enabled = UnstopStore.fcmConnectionProtectionEnabled(appContext)
        return runCatching {
            withService(
                context = appContext,
                operation = "fcm-protection-$trigger",
                fcmProtectionEnabled = enabled,
            ) { Unit }
        }.onFailure { error ->
            PersistentLog.error(
                appContext,
                "Shizuku",
                "Could not reconcile FCM connection protection",
                error,
            )
        }.isSuccess
    }

    private fun <T> withService(
        context: Context,
        operation: String,
        fcmProtectionEnabled: Boolean = UnstopStore.fcmConnectionProtectionEnabled(context),
        block: (IUnstopService) -> T,
    ): T = synchronized(serviceLock) {
        val startedAt = System.currentTimeMillis()
        PersistentLog.debug(context, "UserService", "Binding for $operation")
        val connected = CountDownLatch(1)
        var remote: IUnstopService? = null
        var bindFailure: Throwable? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IUnstopService.Stub.asInterface(binder)
                PersistentLog.debug(context, "UserService", "Connected for $operation")
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (remote == null) {
                    bindFailure = IllegalStateException("UserService disconnected before $operation")
                }
                remote = null
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                if (remote == null) {
                    bindFailure = IllegalStateException("UserService binding died before $operation")
                }
                remote = null
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                bindFailure = IllegalStateException("UserService returned a null binding for $operation")
                connected.countDown()
            }
        }

        mainHandler.post {
            runCatching { Shizuku.bindUserService(serviceArgs, connection) }
                .onFailure { error ->
                    bindFailure = error
                    connected.countDown()
                }
        }
        if (!connected.await(SERVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            PersistentLog.error(context, "UserService", "Bind timed out for $operation")
            throw IllegalStateException("UserService bind timed out")
        }
        val service = remote ?: run {
            val error = bindFailure ?: IllegalStateException("UserService binding failed")
            PersistentLog.error(context, "UserService", "Bind failed for $operation", error)
            throw error
        }
        try {
            if (fcmProtectionEnabled) {
                runCatching {
                    val fileName = service.serviceLogFileName
                    PersistentLog.prepareShizukuLogFile(context, fileName)?.let { path ->
                        service.attachLogPath(path)
                        PersistentLog.debug(context, "UserService", "Attached FCM service log $fileName")
                    } ?: PersistentLog.warn(
                        context,
                        "UserService",
                        "Could not prepare external FCM service log $fileName",
                    )
                }.onFailure { error ->
                    PersistentLog.warn(
                        context,
                        "UserService",
                        "Could not attach external FCM service log",
                        error,
                    )
                }
            }
            val protection = service.configureFcmConnectionProtection(
                fcmProtectionEnabled,
                UnstopStore.fcmPollingIntervalMillis(context),
                operation,
            )
            PersistentLog.debug(context, "UserService", protection)
            block(service).also {
                PersistentLog.debug(
                    context,
                    "UserService",
                    "$operation completed in ${System.currentTimeMillis() - startedAt} ms",
                )
            }
        } catch (error: Throwable) {
            PersistentLog.error(context, "UserService", "$operation failed", error)
            throw error
        } finally {
            val unbindIssued = CountDownLatch(1)
            var unbindFailure: Throwable? = null
            mainHandler.post {
                try {
                    Shizuku.unbindUserService(
                        serviceArgs,
                        connection,
                        !fcmProtectionEnabled,
                    )
                } catch (error: Throwable) {
                    unbindFailure = error
                } finally {
                    unbindIssued.countDown()
                }
            }
            if (!unbindIssued.await(SERVICE_UNBIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                PersistentLog.warn(context, "UserService", "Unbind request timed out after $operation")
            } else if (unbindFailure != null) {
                PersistentLog.warn(
                    context,
                    "UserService",
                    "Unbind request failed after $operation",
                    unbindFailure,
                )
            } else if (fcmProtectionEnabled) {
                PersistentLog.debug(
                    context,
                    "UserService",
                    "Client unbound after $operation; daemon retained for FCM protection",
                )
            } else {
                PersistentLog.debug(
                    context,
                    "UserService",
                    "Client unbound after $operation; service removed because FCM protection is disabled",
                )
            }
        }
    }
}
