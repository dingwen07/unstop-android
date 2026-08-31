package net.extrawdw.apps.unstop

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Privileged daemon that performs package operations and protects the FCM connection. */
class UnstopUserService() : IUnstopService.Stub() {
    /** Shizuku API 13 prefers this constructor when reflectively creating a UserService. */
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val systemCommands = SystemServiceCommandRunner()
    private val fcmExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fcm-connection-protection").apply { isDaemon = true }
    }
    @Volatile
    private var fcmPolling: ScheduledFuture<*>? = null
    @Volatile
    private var fcmPollingIntervalMillis = FcmPollingInterval.DEFAULT_MILLIS
    private var cachedGmsUid: Int? = null
    private var nextMandatoryFcmReconnectElapsed = 0L
    private val serviceLogLock = Any()
    private val pendingServiceLogs = ArrayDeque<String>()
    private val serviceLogTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US)
    private val serviceLogFileName = newServiceLogFileName()
    private var serviceLogFile: File? = null
    private var serviceLogWriter: BufferedWriter? = null

    init {
        serviceLog('I', "Service", "created uid=${Process.myUid()} pid=${Process.myPid()}")
    }

    override fun unstop(
        packageNames: Array<out String>,
        targetUserIds: IntArray,
        trigger: String,
    ): String {
        val packages = packageNames.distinct().sorted()
        val users = targetUserIds.distinct().sorted()
        require(packages.all(::isPackageName)) { "Invalid package name" }
        require(users.all { it >= 0 }) { "Invalid Android user ID" }
        Log.i(TAG, "Unstop started trigger=$trigger packages=${packages.size} users=$users")

        var unstopped = 0
        var failed = 0
        val lines = mutableListOf<String>()
        users.forEach { userId ->
            val state = runSystemCommand(
                SystemServiceCommands.packageManager(
                    "list",
                    "packages",
                    "--user",
                    userId.toString(),
                    "--show-stopped",
                ),
                PACKAGE_COMMAND_TIMEOUT_SECONDS,
                MAX_PACKAGE_LIST_OUTPUT_LENGTH,
            )
            if (!state.succeeded) {
                failed++
                lines += "__UNSTOP_USER__$userId|list_failed|${state.summary.singleLine()}"
                return@forEach
            }

            lines += "__UNSTOP_USER__$userId|listed"
            PackageManagerOutput.selectedStoppedPackages(state.output, packages).forEach { packageName ->
                val result = runSystemCommand(
                    SystemServiceCommands.packageManager(
                        "unstop",
                        "--user",
                        userId.toString(),
                        packageName,
                    ),
                    PACKAGE_COMMAND_TIMEOUT_SECONDS,
                )
                if (result.succeeded) {
                    unstopped++
                    lines += "__UNSTOP_RESULT__$userId|$packageName|unstopped"
                } else {
                    failed++
                    lines += "__UNSTOP_RESULT__$userId|$packageName|failed|${result.summary.singleLine()}"
                }
            }
        }
        lines += "__UNSTOP_UNSTOPPED__$unstopped"
        lines += "__UNSTOP_FAILED__$failed"
        return lines.joinToString("\n").also { report ->
            Log.i(
                TAG,
                "Unstop finished trigger=$trigger unstopped=$unstopped failed=$failed " +
                    "report=${report.singleLine()}",
            )
        }
    }

    override fun listUsers(): String {
        val result = runSystemCommand(
            SystemServiceCommands.packageManager("list", "users"),
            PACKAGE_COMMAND_TIMEOUT_SECONDS,
        )
        check(result.succeeded) { "Could not list Android users (${result.summary})" }
        return result.output
    }

    override fun discoverFcmApps(userId: Int): String {
        require(userId >= 0) { "Invalid Android user ID" }
        val receivers = runSystemCommand(
            SystemServiceCommands.packageManager(
                "query-receivers",
                "--brief",
                "--components",
                "--query-flags",
                FCM_QUERY_FLAGS,
                "--user",
                userId.toString(),
                "-a",
                FCM_RECEIVE_ACTION,
            ),
            DISCOVERY_COMMAND_TIMEOUT_SECONDS,
            MAX_PACKAGE_LIST_OUTPUT_LENGTH,
        )
        check(receivers.succeeded) {
            "Could not query FCM receivers for user $userId (${receivers.summary})"
        }
        val packages = runSystemCommand(
            SystemServiceCommands.packageManager(
                "list",
                "packages",
                "--user",
                userId.toString(),
                "--show-stopped",
            ),
            DISCOVERY_COMMAND_TIMEOUT_SECONDS,
            MAX_PACKAGE_LIST_OUTPUT_LENGTH,
        )
        check(packages.succeeded) {
            "Could not list packages for user $userId (${packages.summary})"
        }
        return buildString {
            appendLine(RECEIVERS_MARKER)
            appendLine(receivers.output)
            appendLine(PACKAGES_MARKER)
            append(packages.output)
        }.trim()
    }

    override fun getServiceLogFileName(): String = serviceLogFileName

    override fun attachLogPath(logPath: String) {
        val file = validateLogFile(logPath)
        synchronized(serviceLogLock) {
            val currentFile = serviceLogFile
            if (currentFile == null || currentFile != file || !currentFile.exists()) {
                runCatching { serviceLogWriter?.close() }
                serviceLogFile = file
                serviceLogWriter = newServiceLogWriter(file, append = true)
                while (pendingServiceLogs.isNotEmpty()) {
                    writeServiceLogLocked(pendingServiceLogs.removeFirst())
                }
            }
        }
    }

    @Synchronized
    override fun configureFcmConnectionProtection(
        enabled: Boolean,
        pollingIntervalMillis: Long,
        trigger: String,
    ): String {
        require(FcmPollingInterval.isSupported(pollingIntervalMillis)) {
            "Unsupported FCM polling interval: $pollingIntervalMillis ms"
        }
        val intervalChanged = fcmPollingIntervalMillis != pollingIntervalMillis
        if (!enabled) {
            val wasActive = isFcmPollingActive()
            fcmPolling?.cancel(false)
            fcmPolling = null
            cachedGmsUid = null
            nextMandatoryFcmReconnectElapsed = 0L
            fcmPollingIntervalMillis = pollingIntervalMillis
            serviceLog(
                'I',
                "FCM",
                "connection protection disabled trigger=$trigger wasActive=$wasActive",
            )
            return "FCM connection protection: disabled"
        }

        if (isFcmPollingActive() && intervalChanged) {
            fcmPolling?.cancel(false)
            fcmPolling = null
        }
        fcmPollingIntervalMillis = pollingIntervalMillis
        if (!isFcmPollingActive()) {
            fcmPolling = fcmExecutor.scheduleWithFixedDelay(
                ::runFcmConnectionProtection,
                pollingIntervalMillis,
                pollingIntervalMillis,
                TimeUnit.MILLISECONDS,
            )
            serviceLog(
                'I',
                "FCM",
                "FCM connection protection started trigger=$trigger " +
                    "interval=${pollingIntervalMillis / 1_000L}s intervalChanged=$intervalChanged",
            )
        }
        return "FCM connection protection: active (${pollingIntervalMillis / 1_000L}s socket poll)"
    }

    override fun destroy() {
        serviceLog('I', "Service", "destroying uid=${Process.myUid()}")
        fcmPolling?.cancel(false)
        fcmExecutor.shutdownNow()
        systemCommands.close()
        synchronized(serviceLogLock) {
            runCatching { serviceLogWriter?.close() }
            serviceLogWriter = null
        }
        exitProcess(0)
    }

    private fun isFcmPollingActive(): Boolean =
        fcmPolling?.let { !it.isCancelled && !it.isDone } == true

    private fun runFcmConnectionProtection() {
        runCatching {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (
                FcmReconnectPolicy.isMandatoryReconnectDue(
                    nowElapsed,
                    nextMandatoryFcmReconnectElapsed,
                )
            ) {
                sendGcmReconnect(nowElapsed, "mandatory")
                return
            }

            val previouslyCachedUid = cachedGmsUid
            val gmsUid = previouslyCachedUid ?: refreshCachedGmsUid()
            if (gmsUid == null) {
                sendGcmReconnect(nowElapsed, "GMS UID unavailable")
                return
            }

            when (FcmSocketProbe.probe(gmsUid)) {
                FcmSocketProbeResult.MATCHED -> Unit
                FcmSocketProbeResult.NO_MATCH -> {
                    serviceLog(
                        'I',
                        "FCM",
                        "FCM socket disconnected cachedGmsUid=$gmsUid " +
                            "pollInterval=${fcmPollingIntervalMillis / 1_000L}s",
                    )
                    if (previouslyCachedUid != null) refreshCachedGmsUid()
                    sendGcmReconnect(nowElapsed, "socket disconnected")
                }
                FcmSocketProbeResult.UNAVAILABLE -> {
                    serviceLog('W', "FCM", "socket table unavailable gmsUid=$gmsUid")
                    sendGcmReconnect(nowElapsed, "socket table unavailable")
                }
            }
        }.onFailure { error ->
            serviceLog(
                'E',
                "FCM",
                "connection-protection poll failed: ${error.stackTraceToString().take(4_000)}",
            )
        }
    }

    private fun refreshCachedGmsUid(): Int? {
        val result = runSystemCommand(
            SystemServiceCommands.packageManager(
                "list",
                "packages",
                "--user",
                OWNER_USER_ID,
                "-U",
                GMS_PACKAGE,
            ),
            PACKAGE_COMMAND_TIMEOUT_SECONDS,
        )
        val resolvedUid = result.takeIf(SystemServiceCommandRunner.Result::succeeded)
            ?.output
            ?.let { PackageManagerOutput.packageUid(it, GMS_PACKAGE) }
        if (resolvedUid != null) {
            cachedGmsUid = resolvedUid
        } else {
            serviceLog('W', "FCM", "could not resolve GMS UID result=${result.summary}")
        }
        return resolvedUid
    }

    private fun sendGcmReconnect(nowElapsed: Long, reason: String) {
        val result = runSystemCommand(
            SystemServiceCommands.activity(
                "broadcast",
                "--user",
                OWNER_USER_ID,
                "-a",
                GCM_RECONNECT_ACTION,
                "-p",
                GMS_PACKAGE,
            ),
            FCM_COMMAND_TIMEOUT_SECONDS,
        )
        if (result.succeeded) {
            nextMandatoryFcmReconnectElapsed =
                FcmReconnectPolicy.nextMandatoryReconnectElapsed(nowElapsed)
            if (reason != MANDATORY_RECONNECT_REASON) {
                serviceLog('I', "FCM", "requested GCM reconnect reason=$reason")
            }
        } else {
            serviceLog(
                'E',
                "FCM",
                "could not request GCM reconnect reason=$reason result=${result.summary}",
            )
        }
    }

    private fun serviceLog(level: Char, component: String, message: String) {
        when (level) {
            'E' -> Log.e("$TAG/$component", message)
            'W' -> Log.w("$TAG/$component", message)
            'D' -> Log.d("$TAG/$component", message)
            else -> Log.i("$TAG/$component", message)
        }
        synchronized(serviceLogLock) {
            val line = "${serviceLogTimeFormat.format(Date())}  $level  $component  ${message.singleLine()}"
            if (serviceLogWriter == null) {
                if (pendingServiceLogs.size >= MAX_PENDING_SERVICE_LOG_LINES) {
                    pendingServiceLogs.removeFirst()
                }
                pendingServiceLogs.addLast(line)
            } else {
                runCatching { writeServiceLogLocked(line) }
                    .onFailure { error ->
                        Log.e(TAG, "Could not append external FCM service log", error)
                        runCatching { serviceLogWriter?.close() }
                        serviceLogWriter = null
                    }
            }
        }
    }

    private fun writeServiceLogLocked(line: String) {
        val file = serviceLogFile ?: return
        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1L
        if (file.length() + lineBytes > MAX_SERVICE_LOG_BYTES) {
            runCatching { serviceLogWriter?.close() }
            serviceLogWriter = newServiceLogWriter(file, append = false)
            serviceLogWriter?.apply {
                appendLine("${serviceLogTimeFormat.format(Date())}  W  Service  log truncated at size limit")
                flush()
            }
        }
        serviceLogWriter?.apply {
            appendLine(line)
            flush()
        }
    }

    private fun newServiceLogWriter(file: File, append: Boolean): BufferedWriter = BufferedWriter(
        OutputStreamWriter(FileOutputStream(file, append), Charsets.UTF_8),
    )

    private fun validateLogFile(logPath: String): File {
        val file = File(logPath).canonicalFile
        val normalizedPath = file.path.replace('\\', '/')
        val requiredSuffix =
            "/Android/data/${BuildConfig.APPLICATION_ID}/files/logs/$serviceLogFileName"
        require(normalizedPath.endsWith(requiredSuffix)) { "Invalid FCM service log path" }
        require(file.isFile) { "FCM service log file does not exist" }
        return file
    }

    private fun newServiceLogFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return "gms-$timestamp-p${Process.myPid()}.log"
    }

    private fun runSystemCommand(
        command: SystemServiceCommand,
        timeoutSeconds: Long,
        maxOutputLength: Int = MAX_OUTPUT_LENGTH,
    ): SystemServiceCommandRunner.Result =
        systemCommands.run(command, timeoutSeconds, maxOutputLength)

    private fun String.singleLine(): String = lineSequence().joinToString(" | ").take(2_000)

    private fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    companion object {
        private const val TAG = "Unstop/UserService"
        private const val PACKAGE_COMMAND_TIMEOUT_SECONDS = 10L
        private const val DISCOVERY_COMMAND_TIMEOUT_SECONDS = 20L
        private const val FCM_COMMAND_TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_LENGTH = 64_000
        private const val MAX_PACKAGE_LIST_OUTPUT_LENGTH = 512_000
        private const val MAX_SERVICE_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_PENDING_SERVICE_LOG_LINES = 256
        private const val OWNER_USER_ID = "0"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val GCM_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT"
        private const val MANDATORY_RECONNECT_REASON = "mandatory"
        private const val FCM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE"
        private const val FCM_QUERY_FLAGS = "131072"
        private const val RECEIVERS_MARKER = "__UNSTOP_RECEIVERS__"
        private const val PACKAGES_MARKER = "__UNSTOP_PACKAGES__"
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    }
}
