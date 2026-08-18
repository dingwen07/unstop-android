package net.extrawdw.apps.unstop

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Short-lived privileged endpoint. It is started only for a scan/run and is unbound immediately;
 * the target FCM packages are never launched.
 */
class UnstopUserService() : IUnstopService.Stub() {
    /** Shizuku API 13 prefers this constructor when reflectively creating a UserService. */
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun runShell(script: String): Int {
        return execute(listOf("/system/bin/sh", "-c", script), 30).first
    }

    override fun runShellWithOutput(script: String): String {
        val (exitCode, output) = execute(listOf("/system/bin/sh", "-c", script), 30)
        return "__UNSTOP_EXIT__$exitCode\n$output"
    }

    override fun listUsers(): String {
        return execute(listOf("/system/bin/pm", "list", "users"), 5).second
    }

    override fun discoverFcmApps(userId: Int): String {
        require(userId >= 0) { "invalid user id" }
        val script = buildString {
            appendLine("echo __UNSTOP_RECEIVERS__")
            appendLine("/system/bin/cmd package query-receivers --brief --components --query-flags 131072 --user $userId -a com.google.android.c2dm.intent.RECEIVE")
            appendLine("echo __UNSTOP_PACKAGES__")
            appendLine("/system/bin/pm list packages --user $userId --show-stopped")
        }
        return execute(listOf("/system/bin/sh", "-c", script), 20).second
    }

    override fun destroy() {
        // Shizuku removes the service record, but it does not kill this process for us.
        // Leaving this as a no-op keeps the old UserService process alive. Shizuku can then
        // reconnect to that stale Binder after an APK update, where newly added AIDL methods
        // appear to return null because the old Stub does not handle their transaction.
        exitProcess(0)
    }

    private fun execute(command: List<String>, timeoutSeconds: Long): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = "unstop-shell-output") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> output.appendLine(line) }
            }
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(2_000)
        return (if (completed) process.exitValue() else 124) to output.toString().trim()
    }
}
