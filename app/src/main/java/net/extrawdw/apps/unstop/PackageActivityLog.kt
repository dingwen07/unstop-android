package net.extrawdw.apps.unstop

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing successful-unstop records, kept separately from diagnostic output. */
internal object PackageActivityLog {
    private const val LOG_DIRECTORY = "package-logs"
    private const val MAX_CURRENT_FILE_BYTES = 512L * 1024L
    private const val MAX_CURRENT_FILE_LINES = 2_000
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L
    private const val MAX_RETAINED_FILES = 12
    private val lock = Any()
    private val timestampFormatter = DateTimeFormatter.ofPattern(
        "uuuu-MM-dd HH:mm:ss.SSS XXX",
        Locale.US,
    )
    private var activeSessionId = ""
    private var activePart = 1

    fun recordUnstopped(context: Context, userId: Int, packageName: String) {
        if (userId < 0 || !isPackageName(packageName)) {
            PersistentLog.warn(
                context,
                "PackageLog",
                "Ignored invalid package activity; user=$userId, package=$packageName",
            )
            return
        }
        runCatching {
            synchronized(lock) {
                val directory = logDirectory(context).apply { mkdirs() }
                val current = currentFile(directory)
                current.appendText(
                    buildString {
                        append(OffsetDateTime.now().format(timestampFormatter))
                        append("  Unstopped  user=")
                        append(userId)
                        append("  package=")
                        append(packageName)
                        appendLine()
                    },
                    Charsets.UTF_8,
                )
                trimCurrentFile(current)
                pruneLocked(directory, current)
            }
        }.onFailure { error ->
            PersistentLog.error(
                context,
                "PackageLog",
                "Could not persist successful unstop for user=$userId, package=$packageName",
                error,
            )
        }
    }

    fun snapshot(context: Context, selectedFileName: String? = null): PersistentLogSnapshot =
        synchronized(lock) {
            val files = listFilesLocked(context)
            val selected = files.firstOrNull { it.name == selectedFileName } ?: files.firstOrNull()
            val text = selected?.let { logDirectory(context).resolve(it.name).readText(Charsets.UTF_8) }
                .orEmpty()
            PersistentLogSnapshot(files = files, selectedFile = selected, text = text)
        }

    fun delete(context: Context, fileName: String): Boolean = synchronized(lock) {
        val directory = logDirectory(context)
        val target = directory.listFiles().orEmpty()
            .firstOrNull { it.isFile && it.name == fileName }
            ?: return false
        val wasCurrentFile = target.name == currentFile(directory).name
        if (!target.delete()) return false
        if (wasCurrentFile) activePart++
        true
    }

    /** Deletes every package activity file while keeping later writes in a fresh part file. */
    fun deleteAll(context: Context): Int = synchronized(lock) {
        val directory = logDirectory(context)
        val currentFileName = currentFile(directory).name
        val targets = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("packages-") && it.extension == "log" }
        var deletedCount = 0
        var deletedCurrentFile = false
        targets.forEach { target ->
            if (target.delete()) {
                deletedCount++
                if (target.name == currentFileName) deletedCurrentFile = true
            }
        }
        if (deletedCurrentFile) activePart++
        deletedCount
    }

    private fun currentFile(directory: File): File {
        val sessionId = PersistentLog.currentSessionId()
        if (sessionId != activeSessionId) {
            activeSessionId = sessionId
            activePart = 1
        }
        val suffix = if (activePart == 1) "" else "-part$activePart"
        return directory.resolve("packages-$sessionId$suffix.log")
    }

    private fun listFilesLocked(context: Context): List<PersistentLogFile> =
        logDirectory(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("packages-") && it.extension == "log" }
            .sortedByDescending(File::lastModified)
            .map { file ->
                PersistentLogFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                )
            }

    private fun trimCurrentFile(file: File) {
        if (file.length() <= MAX_CURRENT_FILE_BYTES) return
        val retained = file.readLines(Charsets.UTF_8).takeLast(MAX_CURRENT_FILE_LINES)
        file.writeText(retained.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun pruneLocked(directory: File, current: File) {
        val oldestFirst = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("packages-") && it.extension == "log" }
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

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))

    private fun logDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, LOG_DIRECTORY)
}
