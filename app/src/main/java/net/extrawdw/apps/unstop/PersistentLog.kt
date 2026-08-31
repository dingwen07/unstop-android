package net.extrawdw.apps.unstop

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class PersistentLogFile(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

internal data class PersistentLogSnapshot(
    val files: List<PersistentLogFile>,
    val selectedFile: PersistentLogFile?,
    val text: String,
)

/**
 * File-based diagnostics retained across process death and device restarts.
 *
 * App-process logs live in no-backup storage. The retained Shizuku FCM watchdog writes one
 * `gms-...` file per service instance under the external app-specific `files/logs` directory.
 * Diagnostics presents both locations as one list.
 */
internal object PersistentLog {
    private const val LOGCAT_TAG = "Unstop"
    private const val LOG_DIRECTORY = "logs"
    private const val MAX_SESSION_FILE_BYTES = 512L * 1024L
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L
    private const val MAX_RETAINED_FILES = 12
    private const val MAX_EXTERNAL_TOTAL_BYTES = 8L * 1024L * 1024L
    private const val MAX_EXTERNAL_RETAINED_FILES = 12
    private const val GMS_LOG_PREFIX = "gms-"
    private val lock = Any()
    private val timestampFormatter = DateTimeFormatter.ofPattern(
        "uuuu-MM-dd HH:mm:ss.SSS XXX",
        Locale.US,
    )
    private val fileTimestampFormatter = DateTimeFormatter.ofPattern(
        "uuuuMMdd-HHmmss-SSS",
        Locale.US,
    )
    private var sessionStem = newSessionStem()
    private var sessionPart = 1

    /** Shared identifier used by parallel per-process log streams. */
    fun currentSessionId(): String = synchronized(lock) {
        sessionStem.removePrefix("unstop-")
    }

    fun debug(context: Context, component: String, message: String) =
        append(context, "DEBUG", Log.DEBUG, component, message, null)

    fun info(context: Context, component: String, message: String) =
        append(context, "INFO", Log.INFO, component, message, null)

    fun warn(context: Context, component: String, message: String, error: Throwable? = null) =
        append(context, "WARN", Log.WARN, component, message, error)

    fun error(context: Context, component: String, message: String, error: Throwable? = null) =
        append(context, "ERROR", Log.ERROR, component, message, error)

    /** Creates the external file as the app UID before Shizuku's shell process appends to it. */
    fun prepareShizukuLogFile(context: Context, fileName: String): String? = synchronized(lock) {
        if (!GMS_LOG_FILE.matches(fileName)) return null
        runCatching {
            val directory = externalLogDirectory(context) ?: return@runCatching null
            if (!directory.exists() && !directory.mkdirs()) return@runCatching null
            if (!directory.isDirectory) return@runCatching null
            val file = directory.resolve(fileName)
            if (!file.exists() && !file.createNewFile()) return@runCatching null
            if (!file.isFile) return@runCatching null
            pruneExternalLogsLocked(directory, current = file)
            file.absolutePath
        }.getOrNull()
    }

    fun snapshot(context: Context, selectedFileName: String? = null): PersistentLogSnapshot =
        synchronized(lock) {
            val files = listFilesLocked(context)
            val selected = files.firstOrNull { it.name == selectedFileName } ?: files.firstOrNull()
            val text = selected
                ?.let { selectedFile -> findLogFileLocked(context, selectedFile.name) }
                ?.let { file -> runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("") }
                .orEmpty()
            PersistentLogSnapshot(files = files, selectedFile = selected, text = text)
        }

    /** Deletes exactly one listed log file; path components supplied by callers are not accepted. */
    fun delete(context: Context, fileName: String): Boolean = synchronized(lock) {
        val appDirectory = logDirectory(context)
        val target = findLogFileLocked(context, fileName)
            ?: return false
        val wasCurrentFile = target.parentFile == appDirectory &&
            target.name == sessionFile(appDirectory).name
        if (!target.delete()) return false
        if (wasCurrentFile) sessionPart++
        true
    }

    /** Deletes every diagnostics file while keeping later writes in a fresh part file. */
    fun deleteAll(context: Context): Int = synchronized(lock) {
        val appDirectory = logDirectory(context)
        val currentFileName = sessionFile(appDirectory).name
        val targets = logDirectories(context)
            .flatMap { directory -> directory.listFiles().orEmpty().filter(File::isFile) }
        var deletedCount = 0
        var deletedCurrentFile = false
        targets.forEach { target ->
            if (target.delete()) {
                deletedCount++
                if (target.parentFile == appDirectory && target.name == currentFileName) {
                    deletedCurrentFile = true
                }
            }
        }
        if (deletedCurrentFile) sessionPart++
        deletedCount
    }

    private fun append(
        context: Context,
        level: String,
        logcatPriority: Int,
        component: String,
        message: String,
        error: Throwable?,
    ) {
        val body = buildString {
            append(message.trim())
            if (error != null) {
                appendLine()
                append(error.stackTraceToString().trim())
            }
        }
        Log.println(logcatPriority, LOGCAT_TAG, "$component: $body")

        val entry = buildString {
            append(OffsetDateTime.now().format(timestampFormatter))
            append("  ")
            append(level.padEnd(5))
            append("  ")
            append(component)
            append("  ")
            append(body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n    "))
            appendLine()
        }
        val entryBytes = entry.toByteArray(Charsets.UTF_8).size

        synchronized(lock) {
            val directory = logDirectory(context).apply { mkdirs() }
            var current = sessionFile(directory)
            if (current.exists() && current.length() + entryBytes > MAX_SESSION_FILE_BYTES) {
                sessionPart++
                current = sessionFile(directory)
            }
            current.appendText(entry, Charsets.UTF_8)
            pruneLocked(directory, current)
        }
    }

    private fun sessionFile(directory: File): File {
        val suffix = if (sessionPart == 1) "" else "-part$sessionPart"
        return directory.resolve("$sessionStem$suffix.log")
    }

    private fun newSessionStem(): String =
        "unstop-${OffsetDateTime.now().format(fileTimestampFormatter)}-p${Process.myPid()}"

    private fun listFilesLocked(context: Context): List<PersistentLogFile> =
        logDirectories(context)
            .flatMap { directory -> directory.listFiles().orEmpty().asIterable() }
            .filter { it.isFile }
            .sortedByDescending(File::lastModified)
            .map { file ->
                PersistentLogFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                )
            }

    private fun pruneLocked(directory: File, current: File) {
        val oldestFirst = directory.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy(File::lastModified)
            .toMutableList()
        var totalBytes = oldestFirst.sumOf(File::length)
        while (oldestFirst.size > MAX_RETAINED_FILES || totalBytes > MAX_TOTAL_BYTES) {
            val candidate = oldestFirst.firstOrNull { it != current } ?: break
            oldestFirst.remove(candidate)
            val length = candidate.length()
            if (candidate.delete()) totalBytes -= length
        }
    }

    private fun pruneExternalLogsLocked(directory: File, current: File?) {
        val oldestFirst = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(GMS_LOG_PREFIX) }
            .sortedBy(File::lastModified)
            .toMutableList()
        val protectedFile = current ?: oldestFirst.lastOrNull()
        var totalBytes = oldestFirst.sumOf(File::length)
        while (
            oldestFirst.size > MAX_EXTERNAL_RETAINED_FILES ||
            totalBytes > MAX_EXTERNAL_TOTAL_BYTES
        ) {
            val candidate = oldestFirst.firstOrNull { it != protectedFile } ?: break
            oldestFirst.remove(candidate)
            val length = candidate.length()
            if (candidate.delete()) totalBytes -= length
        }
    }

    private fun findLogFileLocked(context: Context, fileName: String): File? {
        if (fileName != File(fileName).name) return null
        return logDirectories(context).firstNotNullOfOrNull { directory ->
            directory.listFiles().orEmpty().firstOrNull { file ->
                file.isFile && file.name == fileName
            }
        }
    }

    private fun logDirectories(context: Context): List<File> = buildList {
        add(logDirectory(context))
        externalLogDirectory(context)?.let(::add)
    }.distinctBy { it.absolutePath }

    private fun logDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, LOG_DIRECTORY)

    private fun externalLogDirectory(context: Context): File? =
        context.applicationContext.getExternalFilesDir(null)?.let { File(it, LOG_DIRECTORY) }

    private val GMS_LOG_FILE = Regex("gms-[0-9]{8}-[0-9]{6}-[0-9]{3}-p[0-9]+\\.log")
}
