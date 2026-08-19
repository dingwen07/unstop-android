package net.extrawdw.apps.unstop

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.unstop.ui.theme.UnstopTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PersistentLog.info(this, "App", "MainActivity created; verifying background work")
        UnstopWorkScheduler.ensureScheduled(
            this,
            source = "app_start",
            forceNetworkRegistration = true,
        )
        setContent {
            UnstopTheme {
                UnstopApp()
            }
        }
    }
}

private enum class AppTab(@param:StringRes val labelRes: Int) {
    MONITOR(R.string.tab_monitor),
    APPS(R.string.tab_apps),
}

private enum class LogPage {
    PACKAGES,
    DIAGNOSTICS,
}

@PreviewScreenSizes
@Composable
fun UnstopApp() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.MONITOR) }
    var appReloadVersion by remember { mutableIntStateOf(0) }
    var logPage by rememberSaveable { mutableStateOf<LogPage?>(null) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                icon = {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = stringResource(AppTab.MONITOR.labelRes),
                    )
                },
                label = { Text(stringResource(AppTab.MONITOR.labelRes)) },
                selected = currentTab == AppTab.MONITOR,
                onClick = { currentTab = AppTab.MONITOR },
            )
            item(
                icon = {
                    Icon(
                        Icons.Outlined.Apps,
                        contentDescription = stringResource(AppTab.APPS.labelRes),
                    )
                },
                label = { Text(stringResource(AppTab.APPS.labelRes)) },
                selected = currentTab == AppTab.APPS,
                onClick = { currentTab = AppTab.APPS },
            )
        },
    ) {
        when (currentTab) {
            AppTab.MONITOR -> MonitorScreen(
                onUsersChanged = { appReloadVersion++ },
                onOpenPackageLogs = { logPage = LogPage.PACKAGES },
                onOpenDiagnostics = { logPage = LogPage.DIAGNOSTICS },
            )
            AppTab.APPS -> AppsScreen(reloadVersion = appReloadVersion)
        }
    }

    when (logPage) {
        LogPage.PACKAGES -> PackageActivityScreen(onBack = { logPage = null })
        LogPage.DIAGNOSTICS -> DiagnosticsScreen(onBack = { logPage = null })
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorScreen(
    onUsersChanged: () -> Unit,
    onOpenPackageLogs: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var shizukuStatus by remember { mutableStateOf(ShizukuController.status()) }
    var monitorUsers by remember { mutableStateOf(UnstopStore.monitorUsers(context)) }
    var users by remember { mutableStateOf(FcmRepository.availableUsers(context)) }
    var intervalMinutes by remember { mutableIntStateOf(UnstopStore.intervalMinutes(context)) }
    var periodicEnabled by remember { mutableStateOf(UnstopStore.periodicEnabled(context)) }
    var discoveredApps by remember { mutableStateOf<List<FcmApp>?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }
    val lastRunFlow = remember(context) { UnstopStore.observeLastRun(context) }
    val lastRun by lastRunFlow.collectAsStateWithLifecycle(
        initialValue = remember(context) { UnstopStore.lastRun(context) },
    )
    val minuteTicker = remember {
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(now)
                delay(DateUtils.MINUTE_IN_MILLIS - now % DateUtils.MINUTE_IN_MILLIS)
            }
        }
    }
    val currentTimeMillis by minuteTicker.collectAsStateWithLifecycle(
        initialValue = System.currentTimeMillis(),
    )
    val listState = rememberLazyListState()

    LaunchedEffect(monitorUsers, refreshVersion) {
        val result = withContext(Dispatchers.IO) {
            FcmRepository.refresh(
                context,
                requestedUserIds = monitorUsers,
                loadIcons = false,
            )
        }
        users = result.users
        discoveredApps = result.apps
    }

    val enabledPackages = UnstopStore.enabledAppPackages(context)
    val selectedApps = discoveredApps.orEmpty()
        .filter { it.packageName in enabledPackages }
        .map { it.packageName }
        .toSet()
        .size
    val stoppedSelectedApps = discoveredApps.orEmpty()
        .filter { it.stopped && it.packageName in enabledPackages }
        .map { it.packageName }
        .toSet()
        .size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.monitor_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        shizukuStatus = ShizukuController.status()
                        refreshVersion++
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ShizukuCard(
                    status = shizukuStatus,
                    onAction = {
                        if (shizukuStatus == ShizukuStatus.NOT_RUNNING) {
                            ShizukuController.openManager(context)
                        } else {
                            ShizukuController.requestPermission()
                            shizukuStatus = ShizukuController.status()
                        }
                    },
                    onRefresh = { shizukuStatus = ShizukuController.status() },
                )
            }
            item {
                Card {
                    Column {
                        ListItem(
                            leadingContent = {
                                Icon(Icons.Outlined.Schedule, contentDescription = null)
                            },
                            headlineContent = { Text(stringResource(R.string.periodic_unstop)) },
                            supportingContent = {
                                Text(
                                    if (periodicEnabled) {
                                        pluralStringResource(
                                            R.plurals.periodic_schedule,
                                            selectedApps,
                                            formatInterval(intervalMinutes),
                                            selectedApps,
                                        )
                                    } else {
                                        stringResource(R.string.periodic_disabled)
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = periodicEnabled,
                                    onCheckedChange = {
                                        periodicEnabled = it
                                        UnstopStore.setPeriodicEnabled(context, it)
                                        UnstopWorkScheduler.updateScheduled(
                                            context,
                                            source = "periodic_setting_changed",
                                        )
                                    },
                                )
                            },
                        )
                        IntervalPicker(
                            minutes = intervalMinutes,
                            onMinutesChanged = {
                                intervalMinutes = it
                                UnstopStore.setIntervalMinutes(context, it)
                                UnstopWorkScheduler.updateScheduled(
                                    context,
                                    source = "interval_changed",
                                )
                            },
                        )
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.unstop_now),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.selected_apps_stopped,
                                        stoppedSelectedApps,
                                        stoppedSelectedApps,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Button(
                            onClick = {
                                isRunning = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        UnstopEngine.runAndRecord(context, UnstopTrigger.MANUAL)
                                    }
                                    isRunning = false
                                    refreshVersion++
                                    shizukuStatus = ShizukuController.status()
                                }
                            },
                            enabled = !isRunning && shizukuStatus == ShizukuStatus.READY,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                stringResource(
                                    if (isRunning) R.string.unstopping else R.string.unstop_now,
                                ),
                            )
                        }
                    }
                }
            }
            item {
                Card {
                    Column {
                        ListItem(
                            leadingContent = {
                                Icon(Icons.Outlined.Settings, contentDescription = null)
                            },
                            headlineContent = { Text(stringResource(R.string.users_to_monitor)) },
                            supportingContent = {
                                Text(stringResource(R.string.users_to_monitor_description))
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    refreshVersion++
                                }) {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = stringResource(R.string.refresh_users),
                                    )
                                }
                            },
                        )
                        users.forEach { user ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    val enabled = user.id !in monitorUsers
                                    UnstopStore.setMonitorUser(context, user.id, enabled)
                                    monitorUsers = UnstopStore.monitorUsers(context)
                                    onUsersChanged()
                                },
                                leadingContent = {
                                    Icon(Icons.Outlined.Person, contentDescription = null)
                                },
                                headlineContent = { Text(user.label) },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            if (user.id in monitorUsers) {
                                                R.string.enabled
                                            } else {
                                                R.string.disabled
                                            },
                                        ),
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = user.id in monitorUsers,
                                        onCheckedChange = { enabled ->
                                            UnstopStore.setMonitorUser(context, user.id, enabled)
                                            monitorUsers = UnstopStore.monitorUsers(context)
                                            onUsersChanged()
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        headlineContent = { Text(lastRun.summary) },
                        supportingContent = if (lastRun.timestamp == 0L) null else {
                            {
                                Text(
                                    stringResource(
                                        R.string.last_check_time,
                                        DateUtils.getRelativeTimeSpanString(
                                            lastRun.timestamp,
                                            currentTimeMillis,
                                            DateUtils.MINUTE_IN_MILLIS,
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            item {
                Card {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.package_logs)) },
                        trailingContent = {
                            TextButton(onClick = onOpenPackageLogs) {
                                Text(stringResource(R.string.view))
                            }
                        },
                    )
                }
            }
            item {
                Card {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.diagnostics_log)) },
                        trailingContent = {
                            TextButton(onClick = onOpenDiagnostics) {
                                Text(stringResource(R.string.view))
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageActivityScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LogViewer(
        titleRes = R.string.package_logs,
        noFilesRes = R.string.no_package_log_files,
        emptyRes = R.string.no_package_activity,
        clipboardLabelRes = R.string.package_logs,
        snapshotProvider = { selectedFileName ->
            PackageActivityLog.snapshot(context, selectedFileName)
        },
        deleteSelected = { fileName -> PackageActivityLog.delete(context, fileName) },
        deleteAll = { PackageActivityLog.deleteAll(context) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LogViewer(
        titleRes = R.string.diagnostics,
        noFilesRes = R.string.no_log_files,
        emptyRes = R.string.no_diagnostics,
        clipboardLabelRes = R.string.unstop_diagnostics,
        snapshotProvider = { selectedFileName ->
            PersistentLog.snapshot(context, selectedFileName)
        },
        deleteSelected = { fileName -> PersistentLog.delete(context, fileName) },
        deleteAll = { PersistentLog.deleteAll(context) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewer(
    @StringRes titleRes: Int,
    @StringRes noFilesRes: Int,
    @StringRes emptyRes: Int,
    @StringRes clipboardLabelRes: Int,
    snapshotProvider: (String?) -> PersistentLogSnapshot,
    deleteSelected: (String) -> Boolean,
    deleteAll: () -> Int,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember {
        mutableStateOf(PersistentLogSnapshot(emptyList(), selectedFile = null, text = ""))
    }
    var filesExpanded by remember { mutableStateOf(false) }
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }

    fun load(selectedFileName: String? = snapshot.selectedFile?.name) {
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                snapshotProvider(selectedFileName)
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { snapshotProvider(null) }
    }

    fun deleteCurrent() {
        val selectedFileName = snapshot.selectedFile?.name ?: return
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                deleteSelected(selectedFileName)
                snapshotProvider(null)
            }
        }
    }

    fun deleteAllFiles() {
        showDeleteAllConfirmation = false
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                deleteAll()
                snapshotProvider(null)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onBack,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showDeleteAllConfirmation = true },
                    enabled = snapshot.files.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.delete_all_logs),
                        tint = if (snapshot.files.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
                IconButton(onClick = { load() }) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.refresh_log),
                    )
                }
            }
            LogViewerContent(
                modifier = Modifier.weight(1f),
                snapshot = snapshot,
                filesExpanded = filesExpanded,
                onFilesExpandedChange = { filesExpanded = it },
                onSelectFile = ::load,
                onDelete = ::deleteCurrent,
                noFilesRes = noFilesRes,
                emptyRes = emptyRes,
                clipboardLabelRes = clipboardLabelRes,
            )
        }
    }

    if (showDeleteAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmation = false },
            title = { Text(stringResource(R.string.delete_all_logs_title)) },
            text = { Text(stringResource(R.string.delete_all_logs_message)) },
            confirmButton = {
                TextButton(onClick = ::deleteAllFiles) {
                    Text(
                        stringResource(R.string.delete_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LogViewerContent(
    modifier: Modifier,
    snapshot: PersistentLogSnapshot,
    filesExpanded: Boolean,
    onFilesExpandedChange: (Boolean) -> Unit,
    onSelectFile: (String?) -> Unit,
    onDelete: () -> Unit,
    @StringRes noFilesRes: Int,
    @StringRes emptyRes: Int,
    @StringRes clipboardLabelRes: Int,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardLabel = snapshot.selectedFile?.name ?: stringResource(clipboardLabelRes)
    val fileMenuScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { onFilesExpandedChange(true) },
                    enabled = snapshot.files.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        snapshot.selectedFile?.name ?: stringResource(noFilesRes),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = filesExpanded,
                    onDismissRequest = { onFilesExpandedChange(false) },
                    modifier = Modifier.heightIn(max = 320.dp),
                    scrollState = fileMenuScrollState,
                ) {
                    snapshot.files.forEach { file ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        formatLogSize(file.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onFilesExpandedChange(false)
                                onSelectFile(file.name)
                            },
                        )
                    }
                }
            }
            Text(
                pluralStringResource(
                    R.plurals.log_file_count,
                    snapshot.files.size,
                    snapshot.files.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                        .padding(12.dp),
                ) {
                    Text(
                        snapshot.text.ifBlank { stringResource(emptyRes) },
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDelete,
                enabled = snapshot.selectedFile != null,
            ) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(
                            clipboardLabel,
                            snapshot.text,
                        ),
                    )
                },
                enabled = snapshot.text.isNotBlank(),
            ) {
                Text(stringResource(R.string.copy_selected))
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    status: ShizukuStatus,
    onAction: () -> Unit,
    onRefresh: () -> Unit,
) {
    val (title, description, icon) = when (status) {
        ShizukuStatus.READY -> Triple(
            stringResource(R.string.shizuku_ready),
            stringResource(R.string.shizuku_ready_description),
            Icons.Outlined.CheckCircle,
        )
        ShizukuStatus.PERMISSION_REQUIRED -> Triple(
            stringResource(R.string.shizuku_permission_required),
            stringResource(R.string.shizuku_permission_description),
            Icons.Outlined.ErrorOutline,
        )
        ShizukuStatus.NOT_RUNNING -> Triple(
            stringResource(R.string.shizuku_not_running),
            stringResource(R.string.shizuku_not_running_description),
            Icons.Outlined.CloudOff,
        )
        ShizukuStatus.ERROR -> Triple(
            stringResource(R.string.shizuku_unavailable),
            stringResource(R.string.shizuku_unavailable_description),
            Icons.Outlined.ErrorOutline,
        )
    }
    Card {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != ShizukuStatus.READY) {
                        TextButton(onClick = onAction) {
                            Text(
                                stringResource(
                                    if (status == ShizukuStatus.NOT_RUNNING) {
                                        R.string.open
                                    } else {
                                        R.string.grant
                                    },
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refresh_shizuku_status),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun IntervalPicker(minutes: Int, onMinutesChanged: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.check_interval), Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(formatInterval(minutes)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                UnstopStore.INTERVAL_OPTIONS_MINUTES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(formatInterval(option)) },
                        onClick = {
                            onMinutesChanged(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsScreen(reloadVersion: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<FcmApp>?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var enabledPackages by remember { mutableStateOf(UnstopStore.enabledAppPackages(context)) }

    LaunchedEffect(reloadVersion, refreshVersion) {
        apps = withContext(Dispatchers.IO) {
            FcmRepository.refresh(
                context,
                requestedUserIds = null,
            ).apps
        }
    }

    val appGroups = apps.orEmpty()
        .groupBy { it.packageName }
        .values
        .map(::FcmAppGroup)
        .sortedWith(compareBy { it.label.lowercase(Locale.getDefault()) })
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val matching = appGroups.filter { app ->
        normalizedQuery.isEmpty() ||
            app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
            app.packageName.lowercase(Locale.getDefault()).contains(normalizedQuery)
    }
    val enabledApps = matching.filter {
        it.packageName in enabledPackages
    }
    val otherApps = matching.filterNot {
        it.packageName in enabledPackages
    }
    val monitoredUsers = UnstopStore.monitorUsers(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.fcm_apps), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.fcm_apps_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshVersion++ }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refresh_apps),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
            )

            when {
                apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                apps!!.isEmpty() -> EmptyAppsState()
                matching.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_search_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (enabledApps.isNotEmpty()) {
                        item(key = "enabled-header") {
                            SectionHeader(stringResource(R.string.selected_apps), enabledApps.size)
                        }
                        items(enabledApps, key = { it.packageName }) { app ->
                            FcmAppRow(
                                app = app,
                                userMonitored = app.instances.any { it.userId in monitoredUsers },
                                enabled = true,
                                onEnabledChanged = { enabled ->
                                    enabledPackages = updateAppGroupSelection(
                                        context = context,
                                        group = app,
                                        enabled = enabled,
                                        currentPackages = enabledPackages,
                                    )
                                },
                            )
                        }
                    }
                    item(key = "all-header") {
                        SectionHeader(
                            stringResource(
                                if (enabledApps.isEmpty()) {
                                    R.string.available_fcm_apps
                                } else {
                                    R.string.other_fcm_apps
                                },
                            ),
                            otherApps.size,
                        )
                    }
                    items(otherApps, key = { it.packageName }) { app ->
                        FcmAppRow(
                            app = app,
                            userMonitored = app.instances.any { it.userId in monitoredUsers },
                            enabled = false,
                            onEnabledChanged = { enabled ->
                                enabledPackages = updateAppGroupSelection(
                                    context = context,
                                    group = app,
                                    enabled = enabled,
                                    currentPackages = enabledPackages,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class FcmAppGroup(
    val instances: List<FcmApp>,
) {
    private val representative: FcmApp get() = instances.first()
    val packageName: String get() = representative.packageName
    val label: String get() = representative.label
    val icon get() = representative.icon
    val stopped: Boolean get() = instances.any { it.stopped }
}

private fun updateAppGroupSelection(
    context: android.content.Context,
    group: FcmAppGroup,
    enabled: Boolean,
    currentPackages: Set<String>,
): Set<String> {
    UnstopStore.setAppEnabled(context, group.packageName, enabled)
    return if (enabled) {
        currentPackages + group.packageName
    } else {
        currentPackages - group.packageName
    }
}

@Composable
private fun FcmAppRow(
    app: FcmAppGroup,
    userMonitored: Boolean,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = userMonitored) { onEnabledChanged(!enabled) },
        leadingContent = { AppIcon(app) },
        headlineContent = {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !userMonitored -> stringResource(R.string.no_monitored_instance)
                        app.stopped -> stringResource(R.string.flag_stopped_set)
                        else -> stringResource(R.string.not_stopped)
                    },
                    color = if (userMonitored) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = enabled,
                enabled = userMonitored,
                onCheckedChange = onEnabledChanged,
            )
        },
    )
}

@Composable
private fun AppIcon(app: FcmAppGroup) {
    val icon = app.icon
    if (icon != null) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    app.label.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        stringResource(R.string.section_count, title, count),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyAppsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Apps,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.no_fcm_packages), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.no_fcm_packages_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatInterval(minutes: Int): String = if (minutes % 60 == 0) {
    val hours = minutes / 60
    pluralStringResource(R.plurals.hours, hours, hours)
} else {
    pluralStringResource(R.plurals.minutes, minutes, minutes)
}

private fun formatLogSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    else -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
}

@Preview(showBackground = true)
@Composable
private fun FcmAppRowPreview() {
    UnstopTheme {
        FcmAppRow(
            app = FcmAppGroup(
                listOf(FcmApp("com.example.mail", "Example Mail", 0, "Owner (user 0)", stopped = true, icon = null)),
            ),
            userMonitored = true,
            enabled = true,
            onEnabledChanged = {},
        )
    }
}
