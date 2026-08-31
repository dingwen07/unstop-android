package net.extrawdw.apps.unstop

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

internal data class UserOption(
    val id: Int,
    val label: String,
)

internal data class FcmApp(
    val packageName: String,
    val label: String,
    val userId: Int,
    val userLabel: String,
    val stopped: Boolean,
    val icon: Bitmap?,
)

internal data class FcmDiscoveryResult(
    val users: List<UserOption>,
    val apps: List<FcmApp>,
)

/** Discovers FCM receiver packages without starting any application process. */
internal object FcmRepository {
    private const val FCM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE"
    private const val ICON_SIZE_PX = 96
    private const val RECEIVERS_MARKER = "__UNSTOP_RECEIVERS__"
    private const val PACKAGES_MARKER = "__UNSTOP_PACKAGES__"
    private val userInfoPattern = Regex("UserInfo\\{(\\d+):(.*):[0-9a-fA-F]+\\}")
    private val packageStatePattern = Regex("^package:([^ ]+)(?: stopped=(true|false))?")

    fun availableUsers(context: Context): List<UserOption> = fallbackUserNames(context)
        .toSortedMap()
        .map { (id, name) -> UserOption(id, context.getString(R.string.user_label_format, name, id)) }

    /** Lists users and scans the requested profiles as one short-lived Shizuku service batch. */
    fun refresh(
        context: Context,
        requestedUserIds: Collection<Int>?,
        loadIcons: Boolean = true,
    ): FcmDiscoveryResult {
        val names = fallbackUserNames(context)
        requestedUserIds.orEmpty().forEach { userId ->
            names.putIfAbsent(userId, fallbackUserName(context, userId))
        }

        val binderBatch = if (ShizukuController.status() == ShizukuStatus.READY) {
            ShizukuController.discoverFcmApps(context, requestedUserIds)
        } else {
            null
        }
        val binderUsers = parseBinderUsers(context, binderBatch?.usersOutput.orEmpty())
        binderUsers.forEach { (id, name) -> names[id] = name }
        if (binderBatch != null) {
            PersistentLog.info(
                context,
                "Discovery",
                "Listed Android users through Shizuku Binder commands; users=${binderUsers.keys.sorted()}",
            )
        }

        val usersToScan = requestedUserIds
            ?.distinct()
            ?.sorted()
            ?: binderUsers.keys.sorted().ifEmpty { names.keys.sorted() }
        usersToScan.forEach { userId ->
            names.putIfAbsent(userId, fallbackUserName(context, userId))
        }
        val users = names.toSortedMap().map { (id, name) ->
            UserOption(id, context.getString(R.string.user_label_format, name, id))
        }
        val userLabels = users.associate { it.id to it.label }
        val apps = discoverFromSnapshots(
            context = context,
            userIds = usersToScan,
            loadIcons = loadIcons,
            userLabels = userLabels,
            snapshotOutputs = binderBatch?.snapshotsByUser.orEmpty(),
        )
        return FcmDiscoveryResult(users, apps)
    }

    private fun fallbackUserNames(context: Context): LinkedHashMap<Int, String> {
        val names = linkedMapOf(0 to context.getString(R.string.owner))
        UnstopStore.monitorUsers(context).forEach { userId ->
            names.putIfAbsent(userId, fallbackUserName(context, userId))
        }
        return names
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun discoverFromSnapshots(
        context: Context,
        userIds: Collection<Int>,
        loadIcons: Boolean = true,
        userLabels: Map<Int, String> = emptyMap(),
        snapshotOutputs: Map<Int, String>,
    ): List<FcmApp> {
        val pm = context.packageManager
        val intent = Intent(FCM_RECEIVE_ACTION)
        val users = userIds.distinct().sorted()
        PersistentLog.info(
            context,
            "Discovery",
            "Starting FCM discovery; users=$users, loadIcons=$loadIcons",
        )
        val apps = users.flatMap { userId ->
            val hasBinderSnapshot = userId in snapshotOutputs
            val binderSnapshot = snapshotOutputs[userId]
                ?.let(::parseSnapshot)
                ?: PackageSnapshot.empty()
            val packageNames = if (hasBinderSnapshot) {
                binderSnapshot.receiverPackages
            } else if (userId == 0) {
                directReceiverPackages(pm, intent)
            } else {
                emptySet()
            }
            PersistentLog.info(
                context,
                "Discovery",
                "User $userId scan; receiverPackages=${packageNames.size}, " +
                    "stoppedPackages=${binderSnapshot.stoppedPackages.count { it.value }}, " +
                    "source=${if (hasBinderSnapshot) "Shizuku Binder" else if (userId == 0) "PackageManager fallback" else "none"}",
            )

            packageNames.mapNotNull { packageName ->
                if (packageName == context.packageName || packageName == "android") return@mapNotNull null
                val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                val flags = appInfo?.flags?.toInt() ?: 0
                val systemFlags = ApplicationInfo.FLAG_SYSTEM.toInt() or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP.toInt()
                if (flags and systemFlags != 0) return@mapNotNull null
                FcmApp(
                    packageName = packageName,
                    label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                        ?: packageName,
                    userId = userId,
                    userLabel = userLabels[userId] ?: fallbackUserLabel(context, userId),
                    stopped = binderSnapshot.stoppedPackages[packageName]
                        ?: (flags and ApplicationInfo.FLAG_STOPPED.toInt() != 0),
                    icon = if (loadIcons && appInfo != null) {
                        runCatching { pm.getApplicationIcon(appInfo).toBitmap(ICON_SIZE_PX) }.getOrNull()
                    } else {
                        null
                    },
                )
            }
        }.distinctBy { it.userId to it.packageName }
            .sortedWith(compareBy<FcmApp> { it.userId }.thenBy { it.label.lowercase() })
        PersistentLog.info(
            context,
            "Discovery",
            "Finished FCM discovery; appInstances=${apps.size}, uniquePackages=${apps.map { it.packageName }.toSet().size}",
        )
        return apps
    }

    private fun directReceiverPackages(pm: PackageManager, intent: Intent): Set<String> {
        val receivers: List<ResolveInfo> = runCatching {
            pm.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        return receivers.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun parseSnapshot(output: String): PackageSnapshot {
        if (output.isBlank()) return PackageSnapshot.empty()
        val receiverPackages = linkedSetOf<String>()
        val stoppedPackages = linkedMapOf<String, Boolean>()
        var section = ""
        output.lineSequence().forEach { line ->
            when (line.trim()) {
                RECEIVERS_MARKER -> section = RECEIVERS_MARKER
                PACKAGES_MARKER -> section = PACKAGES_MARKER
                else -> when (section) {
                    RECEIVERS_MARKER -> line.substringBefore('/').trim()
                        .takeIf { isPackageName(it) }
                        ?.let(receiverPackages::add)
                    PACKAGES_MARKER -> packageStatePattern.matchEntire(line.trim())?.let { match ->
                        val packageName = match.groupValues[1]
                        if (isPackageName(packageName)) {
                            stoppedPackages[packageName] = match.groupValues[2] == "true"
                        }
                    }
                }
            }
        }
        return PackageSnapshot(receiverPackages, stoppedPackages)
    }

    private fun parseBinderUsers(context: Context, output: String): Map<Int, String> = output.lineSequence()
        .mapNotNull { line ->
            val match = userInfoPattern.find(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val name = match.groupValues[2].takeUnless { it.isBlank() || it == "null" }
                ?: fallbackUserName(context, id)
            id to name
        }
        .toMap()

    private fun fallbackUserName(context: Context, userId: Int): String = if (userId == 0) {
        context.getString(R.string.owner)
    } else {
        context.getString(R.string.user_fallback, userId)
    }

    private fun fallbackUserLabel(context: Context, userId: Int): String = context.getString(
        R.string.user_label_format,
        fallbackUserName(context, userId),
        userId,
    )

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))

    private fun Drawable.toBitmap(size: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        setBounds(0, 0, size, size)
        draw(Canvas(bitmap))
        return bitmap
    }

    private data class PackageSnapshot(
        val receiverPackages: Set<String>,
        val stoppedPackages: Map<String, Boolean>,
    ) {
        companion object {
            fun empty() = PackageSnapshot(emptySet(), emptyMap())
        }
    }
}
