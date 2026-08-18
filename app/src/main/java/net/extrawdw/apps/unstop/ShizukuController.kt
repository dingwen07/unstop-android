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
    private val exitPattern = Regex("__UNSTOP_EXIT__(-?\\d+)")
    private val unstoppedPattern = Regex("__UNSTOP_UNSTOPPED__(\\d+)")
    private val successfulUnstopPattern = Regex(
        "__UNSTOP_RESULT__(\\d+)\\|([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)\\|unstopped",
    )
    private val userIdPattern = Regex("UserInfo\\{(\\d+):")

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UnstopUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("unstop")
        .tag("unstop-v1")
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
            "Running one shell script; users=$users, selectedPackageCount=${packages.size}",
        )
        val script = buildUnstopScript(userIds, packages)
        val diagnosticResult = runCatching {
            withService(appContext, "unstop") { service -> service.runShellWithOutput(script) }
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
        val exitCode = exitPattern.find(diagnosticOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val unstopped = unstoppedPattern.find(diagnosticOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        PersistentLog.info(
            appContext,
            "Shizuku",
            "Shell script completed; exitCode=$exitCode, unstopped=$unstopped",
        )
        return ShizukuRunResult(
            status = ShizukuStatus.READY,
            attempted = unstopped,
            commandSucceeded = exitCode == 0,
        )
    }

    private fun buildUnstopScript(userIds: Collection<Int>, packageNames: Collection<String>): String =
        buildString {
            appendLine("unstopped=0")
            appendLine("failed=0")
            userIds.distinct().sorted().forEach { userId ->
                appendLine("state=${'$'}(/system/bin/pm list packages --user $userId --show-stopped 2>/dev/null)")
                appendLine("state_exit=${'$'}?")
                appendLine("if [ \"${'$'}state_exit\" -ne 0 ]; then")
                appendLine("  echo \"__UNSTOP_USER__$userId|list_failed|${'$'}state_exit\"")
                appendLine("  failed=1")
                appendLine("else")
                appendLine("  echo \"__UNSTOP_USER__$userId|listed\"")
                packageNames.forEach { packageName ->
                    val quotedPackage = shellQuote(packageName)
                    appendLine("  case ${'$'}state in")
                    appendLine("    *\"package:$packageName stopped=true\"*)")
                    appendLine("      echo \"__UNSTOP_MATCH__$userId|$packageName\"")
                    appendLine("      if /system/bin/pm unstop --user $userId $quotedPackage >/dev/null 2>&1; then")
                    appendLine("        unstopped=${'$'}((unstopped + 1))")
                    appendLine("        echo \"__UNSTOP_RESULT__$userId|$packageName|unstopped\"")
                    appendLine("      else")
                    appendLine("        failed=1")
                    appendLine("        echo \"__UNSTOP_RESULT__$userId|$packageName|failed\"")
                    appendLine("      fi")
                    appendLine("      ;;")
                    appendLine("  esac")
                }
                appendLine("fi")
            }
            appendLine("echo __UNSTOP_UNSTOPPED__${'$'}unstopped")
            appendLine("exit ${'$'}failed")
        }

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))

    /** Lists users and scans every requested user through one short-lived UserService binding. */
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

    private fun <T> withService(
        context: Context,
        operation: String,
        block: (IUnstopService) -> T,
    ): T = synchronized(serviceLock) {
        val startedAt = System.currentTimeMillis()
        PersistentLog.debug(context, "UserService", "Binding for $operation")
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
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
                connected.countDown()
                disconnected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                if (remote == null) {
                    bindFailure = IllegalStateException("UserService binding died before $operation")
                }
                connected.countDown()
                disconnected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                bindFailure = IllegalStateException("UserService returned a null binding for $operation")
                connected.countDown()
                disconnected.countDown()
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
                    Shizuku.unbindUserService(serviceArgs, connection, true)
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
            } else if (!disconnected.await(SERVICE_UNBIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                PersistentLog.warn(
                    context,
                    "UserService",
                    "No disconnect callback after $operation; the next bind may be delayed",
                )
            } else {
                PersistentLog.debug(context, "UserService", "Disconnected after $operation")
            }
        }
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
