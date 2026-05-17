package lilmuff1.bsml.ui

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import lilmuff1.bsml.R
import lilmuff1.bsml.config.DEFAULT_CAPTURE_PACKAGES
import lilmuff1.bsml.service.AssetProxyService
import lilmuff1.bsml.service.LocalVpnService
import lilmuff1.bsml.state.CleanupReasonSpec
import lilmuff1.bsml.state.LatestFingerprintRepository
import lilmuff1.bsml.state.ModFilesRepository
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.ui.theme.BSMLTheme
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

private data class LaunchableAppOption(
    val packageName: String,
    val label: String,
    val icon: Bitmap
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BSMLTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isVpnRunning by VpnLogRepository.isRunning.collectAsState()
    val isAssetProxyRunning by VpnLogRepository.isAssetProxyRunning.collectAsState()
    val isAutoVpnDisableEnabled by VpnLogRepository.isAutoVpnDisableEnabled.collectAsState()
    val isIpFilterEnabled by VpnLogRepository.isIpFilterEnabled.collectAsState()
    val ipFilterText by VpnLogRepository.ipFilterText.collectAsState()
    val packageText by VpnLogRepository.packageText.collectAsState()
    val autoLaunchPackage by VpnLogRepository.autoLaunchPackage.collectAsState()
    val portText by VpnLogRepository.portText.collectAsState()
    val preparation by ModFilesRepository.preparation.collectAsState()
    val latestFingerprintState by LatestFingerprintRepository.state.collectAsState()
    val isRunning = isVpnRunning || isAssetProxyRunning
    val scope = rememberCoroutineScope()
    var pendingCleanupMode by remember { mutableStateOf(false) }
    var pendingCleanupReason by remember { mutableStateOf<CleanupReasonSpec?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val modFolderLabel = stringResource(R.string.label_mod_folder)
    val modFolderNotSelected = stringResource(R.string.value_mod_folder_not_selected)
    val stopLabel = stringResource(R.string.button_stop)
    val installLabel = stringResource(R.string.button_install_mod)
    val refreshLabel = stringResource(R.string.button_refresh_mod_files)
    val preparingLabel = stringResource(R.string.status_mod_preparing)
    val readyLabel = stringResource(R.string.status_mod_ready)
    val emptyLabel = stringResource(R.string.status_mod_not_prepared)
    val errorPrefix = stringResource(R.string.status_mod_error_prefix)
    val latestFingerprintLabel = stringResource(R.string.label_latest_fingerprint)
    val latestFingerprintNotLoaded = stringResource(R.string.value_latest_fingerprint_not_loaded)
    val latestGameServerLabel = stringResource(R.string.label_latest_game_server)
    val latestGameServerNotLoaded = stringResource(R.string.value_latest_game_server_not_loaded)
    val selectedModFolderName = preparation.folderName
    val isInstallEnabled = !isRunning && selectedModFolderName != null

    LaunchedEffect(context) {
        ModFilesRepository.refreshState(context)
        LatestFingerprintRepository.refreshState(context)
    }

    val launchableApps by remember(context, packageText) {
        mutableStateOf(loadLaunchableAppOptions(context, packageText))
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            startMonitoring(
                context,
                cleanupMode = pendingCleanupMode,
                cleanupReason = pendingCleanupReason
            )
            if (VpnLogRepository.captureSettingsNow().autoLaunchPackage != null) {
                launchConfiguredGameWhenVpnReady(context)
            }
        } else {
            VpnLogRepository.setStatus(context.getString(R.string.status_vpn_permission_denied))
            VpnLogRepository.log(context.getString(R.string.log_vpn_permission_denied))
        }
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching { ModFilesRepository.takePersistablePermission(context, uri) }
            ModFilesRepository.setTreeUri(context, uri)
            scope.launch {
                ModFilesRepository.prepareFiles(context)
            }
        }
    }
    fun requestStart(cleanupMode: Boolean, cleanupReason: CleanupReasonSpec?) {
        pendingCleanupMode = cleanupMode
        pendingCleanupReason = cleanupReason
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent == null) {
            startMonitoring(context, cleanupMode = cleanupMode, cleanupReason = cleanupReason)
            if (VpnLogRepository.captureSettingsNow().autoLaunchPackage != null) {
                launchConfiguredGameWhenVpnReady(context)
            }
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    if (showSettings) {
        SettingsDialog(
            isRunning = isRunning,
            isAutoVpnDisableEnabled = isAutoVpnDisableEnabled,
            isIpFilterEnabled = isIpFilterEnabled,
            ipFilterText = ipFilterText,
            packageText = packageText,
            autoLaunchPackage = autoLaunchPackage,
            launchableApps = launchableApps,
            portText = portText,
            latestFingerprintState = latestFingerprintState,
            latestFingerprintLabel = latestFingerprintLabel,
            latestFingerprintNotLoaded = latestFingerprintNotLoaded,
            latestGameServerLabel = latestGameServerLabel,
            latestGameServerNotLoaded = latestGameServerNotLoaded,
            onDismiss = { showSettings = false },
            onFetchLatest = {
                scope.launch {
                    LatestFingerprintRepository.fetchLatest(context)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(
                    enabled = !latestFingerprintState.isFetching,
                    onClick = { showSettings = true }
                ) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$modFolderLabel: ${selectedModFolderName ?: modFolderNotSelected}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (selectedModFolderName != null) {
                    IconButton(
                        enabled = !isRunning && !preparation.isPreparing,
                        onClick = { ModFilesRepository.setTreeUri(context, null) }
                    ) {
                        Text("×", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            if (selectedModFolderName == null) {
                OutlinedButton(
                    enabled = !isRunning && !preparation.isPreparing,
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_select_mod_folder))
                }
            }
            when {
                preparation.isPreparing -> {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    if (preparation.totalCount > 0) {
                        CleanLinearProgressIndicator(
                            progress = preparation.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CleanIndeterminateProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (preparation.totalCount > 0) {
                            "$preparingLabel ${preparation.preparedCount}/${preparation.totalCount}"
                        } else {
                            preparingLabel
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                preparation.error != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$errorPrefix ${preparation.error}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (selectedModFolderName != null) {
                            IconButton(
                                enabled = !isRunning,
                                onClick = {
                                    scope.launch {
                                        ModFilesRepository.prepareFiles(context)
                                    }
                                }
                            ) {
                                Text("↻", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                selectedModFolderName != null && preparation.isReady -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$readyLabel ${preparation.preparedCount}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            enabled = !isRunning,
                            onClick = {
                                scope.launch {
                                    ModFilesRepository.prepareFiles(context)
                                }
                            }
                        ) {
                            Text("↻", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                selectedModFolderName != null -> {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = emptyLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            enabled = !isRunning,
                            onClick = {
                                scope.launch {
                                    ModFilesRepository.prepareFiles(context)
                                }
                            }
                        ) {
                            Text("↻", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    enabled = isRunning || isInstallEnabled,
                    onClick = {
                        if (isRunning) {
                            stopMonitoring(context)
                        } else {
                            requestStart(cleanupMode = false, cleanupReason = null)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = if (isRunning) "■" else "↓",
                        style = MaterialTheme.typography.titleMedium
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) stopLabel else if (preparation.isPreparing) preparingLabel else installLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    enabled = !isRunning && !preparation.isPreparing,
                    onClick = {
                        val cleanupReason = VpnLogRepository.cleanupDeleteReason()
                        cleanupReason?.let { reason ->
                            VpnLogRepository.log("UI cleanup queued reason=${reason.code} ${reason.name}")
                        }
                        requestStart(
                            cleanupMode = true,
                            cleanupReason = cleanupReason
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("✕", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.button_remove_mod))
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    isRunning: Boolean,
    isAutoVpnDisableEnabled: Boolean,
    isIpFilterEnabled: Boolean,
    ipFilterText: String,
    packageText: String,
    autoLaunchPackage: String?,
    launchableApps: List<LaunchableAppOption>,
    portText: String,
    latestFingerprintState: lilmuff1.bsml.state.LatestFingerprintState,
    latestFingerprintLabel: String,
    latestFingerprintNotLoaded: String,
    latestGameServerLabel: String,
    latestGameServerNotLoaded: String,
    onDismiss: () -> Unit,
    onFetchLatest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_done))
            }
        },
        title = {
            Text(stringResource(R.string.title_settings))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_auto_disable_vpn),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = isAutoVpnDisableEnabled,
                        onCheckedChange = VpnLogRepository::setAutoVpnDisableEnabled
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_ip_filter),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = isIpFilterEnabled,
                        onCheckedChange = VpnLogRepository::setIpFilterEnabled,
                        enabled = !isRunning
                    )
                }
                if (isIpFilterEnabled) {
                    ArrayTextField(
                        value = ipFilterText,
                        onValueChange = VpnLogRepository::setIpFilterText,
                        enabled = !isRunning,
                        label = stringResource(R.string.label_ip_hosts)
                    )
                } else {
                    ArrayTextField(
                        value = packageText,
                        onValueChange = VpnLogRepository::setPackageText,
                        enabled = !isRunning,
                        label = stringResource(R.string.label_package_names)
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = VpnLogRepository::setPortText,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning,
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (launchableApps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.label_auto_launch_app),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                VpnLogRepository.setAutoLaunchPackage(null)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = autoLaunchPackage == null,
                            onClick = { VpnLogRepository.setAutoLaunchPackage(null) }
                        )
                        Column(
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.option_no_auto_launch),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    launchableApps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    VpnLogRepository.setAutoLaunchPackage(app.packageName)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoLaunchPackage == app.packageName,
                                onClick = { VpnLogRepository.setAutoLaunchPackage(app.packageName) }
                            )
                            Image(
                                bitmap = app.icon.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .width(28.dp)
                                    .height(28.dp)
                            )
                            Column(
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.status_auto_launch_apps_not_found),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "$latestFingerprintLabel: ${latestFingerprintState.savedRootSha ?: latestFingerprintNotLoaded}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "$latestGameServerLabel: ${latestFingerprintState.savedGameServer ?: latestGameServerNotLoaded}",
                    style = MaterialTheme.typography.bodyMedium
                )
                when {
                    latestFingerprintState.isFetching -> {
                        CleanLinearProgressIndicator(
                            progress = latestFingerprintState.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = latestFingerprintState.message ?: "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    latestFingerprintState.error != null -> {
                        Text(
                            text = stringResource(
                                R.string.status_latest_fingerprint_error_prefix,
                                latestFingerprintState.error ?: ""
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    !latestFingerprintState.message.isNullOrBlank() -> {
                        Text(
                            text = latestFingerprintState.message ?: "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                OutlinedButton(
                    enabled = !isRunning && !latestFingerprintState.isFetching,
                    onClick = onFetchLatest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_fetch_latest_fingerprint))
                }
            }
        }
    )
}

@Composable
private fun CleanLinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

@Composable
private fun CleanIndeterminateProgressIndicator(
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        modifier = modifier,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp
    )
}

@Composable
private fun ArrayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        minLines = 2,
        supportingText = {
            Text(stringResource(R.string.hint_array_separator))
        }
    )
}

private fun startMonitoring(
    context: Context,
    cleanupMode: Boolean,
    cleanupReason: CleanupReasonSpec?
) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, AssetProxyService::class.java)
            .setAction(AssetProxyService.ACTION_START)
    )
    ContextCompat.startForegroundService(
        context,
        Intent(context, LocalVpnService::class.java)
            .setAction(LocalVpnService.ACTION_START)
            .putExtra(LocalVpnService.EXTRA_CLEANUP_MODE, cleanupMode)
            .putExtra(LocalVpnService.EXTRA_CLEANUP_REASON_CODE, cleanupReason?.code ?: -1)
            .putExtra(LocalVpnService.EXTRA_CLEANUP_REASON_NAME, cleanupReason?.name)
    )
}

private fun stopMonitoring(context: Context) {
    context.startService(
        Intent(context, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP)
    )
    context.startService(
        Intent(context, AssetProxyService::class.java).setAction(AssetProxyService.ACTION_STOP)
    )
}

private fun launchConfiguredGame(context: Context) {
    val settings = VpnLogRepository.captureSettingsNow()
    val packageNames = buildList {
        settings.autoLaunchPackage?.let { add(it) }
        addAll(
            settings.packageText
                .split(',', '\n', '\r', '\t', ' ')
                .map(String::trim)
                .filter(String::isNotEmpty)
        )
        if (isEmpty()) {
            addAll(
                DEFAULT_CAPTURE_PACKAGES
                    .split(',', '\n', '\r', '\t', ' ')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            )
        }
    }.distinct()

    for (packageName in packageNames) {
        val intent = findLaunchIntentForPackage(context, packageName) ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                context.startActivity(intent)
                VpnLogRepository.log("UI launched game package=$packageName")
            }.onFailure { error ->
                VpnLogRepository.log(
                    "UI game launch failed package=$packageName error=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
                )
            }
        }
        return
    }

    VpnLogRepository.log("UI game launch failed no launchable package found")
}

private fun loadLaunchableAppOptions(context: Context, packageText: String): List<LaunchableAppOption> {
    val packageManager = context.packageManager
    val packageNames = packageText
        .split(',', '\n', '\r', '\t', ' ')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty {
            DEFAULT_CAPTURE_PACKAGES
                .split(',', '\n', '\r', '\t', ' ')
                .map(String::trim)
                .filter(String::isNotEmpty)
        }

    return packageNames.mapNotNull { packageName ->
        val intent = findLaunchIntentForPackage(context, packageName) ?: return@mapNotNull null
        runCatching {
            val activityInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val appInfo = activityInfo?.applicationInfo ?: packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(appInfo).toString()
            val icon = packageManager.getApplicationIcon(appInfo).toBitmap(96, 96)
            LaunchableAppOption(
                packageName = packageName,
                label = label,
                icon = icon
            )
        }.getOrNull()
    }
}

private fun findLaunchIntentForPackage(context: Context, packageName: String): Intent? {
    val packageManager = context.packageManager
    packageManager.getLaunchIntentForPackage(packageName)?.let { return it }

    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(packageName)
    }
    val resolveInfo = runCatching {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
    }.getOrDefault(emptyList()).firstOrNull()

    val activityInfo = resolveInfo?.activityInfo ?: return null
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(activityInfo.packageName, activityInfo.name)
    }
}

private fun launchConfiguredGameWhenVpnReady(context: Context) {
    thread(name = "bsml-auto-launch-game", start = true) {
        repeat(20) { attempt ->
            if (VpnLogRepository.isVpnRunningNow()) {
                launchConfiguredGame(context)
                return@thread
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (attempt == 19) {
                launchConfiguredGame(context)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    BSMLTheme {
        MainScreen()
    }
}
