package lilmuff1.bsml.ui

import android.app.Activity.RESULT_OK
import android.Manifest
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

private data class LaunchableAppOption(
    val packageName: String,
    val label: String,
    val icon: Bitmap
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnLogRepository.initialize(this)
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
    val isInstallResultNotificationsEnabled by VpnLogRepository.isInstallResultNotificationsEnabled.collectAsState()
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
    var pendingManualLaunchHint by remember { mutableStateOf<PendingManualLaunchHint?>(null) }
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
        withContext(Dispatchers.IO) {
            ModFilesRepository.refreshState(context)
            LatestFingerprintRepository.refreshState(context)
        }
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
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        VpnLogRepository.setInstallResultNotificationsEnabled(context, granted)
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
            isInstallResultNotificationsEnabled = isInstallResultNotificationsEnabled,
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
            onInstallResultNotificationsChange = { enabled ->
                if (!enabled) {
                    VpnLogRepository.setInstallResultNotificationsEnabled(context, false)
                } else if (
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    VpnLogRepository.setInstallResultNotificationsEnabled(context, true)
                }
            },
            onFetchLatest = {
                scope.launch {
                    LatestFingerprintRepository.fetchLatest(context)
                }
            }
        )
    }

    pendingManualLaunchHint?.let { hint ->
        AlertDialog(
            onDismissRequest = { pendingManualLaunchHint = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingManualLaunchHint = null
                        requestStart(
                            cleanupMode = hint.cleanupMode,
                            cleanupReason = hint.cleanupReason
                        )
                    }
                ) {
                    Text(stringResource(R.string.button_continue))
                }
            },
            title = {
                Text(stringResource(R.string.title_manual_launch_needed))
            },
            text = {
                Text(
                    text = stringResource(
                        if (hint.cleanupMode) {
                            R.string.message_manual_launch_remove
                        } else {
                            R.string.message_manual_launch_install
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Локальный VPN и прокси для установки и удаления модов",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsGearButton(
                                enabled = !latestFingerprintState.isFetching,
                                onClick = { showSettings = true }
                            )
                        }
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Что делать")
                        GuidanceStep(number = 1, text = "Выбери папку мода и дождись подготовки файлов")
                        GuidanceStep(number = 2, text = "Нажми установить или удалить мод")
                        GuidanceStep(number = 3, text = if (autoLaunchPackage != null) "Игра запустится автоматически, дождись входа в меню. При большом моде это может занять заметное время" else "Открой игру вручную и дождись входа в меню. При большом моде это может занять заметное время")
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Мод")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedModFolderName ?: modFolderNotSelected,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 30.sp),
                                fontWeight = FontWeight.SemiBold
                            )
                            if (selectedModFolderName != null) {
                                IconButton(
                                    enabled = !isRunning && !preparation.isPreparing,
                                    onClick = { ModFilesRepository.setTreeUri(context, null) },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.button_clear_mod_folder),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                        if (selectedModFolderName == null) {
                            OutlinedButton(
                                enabled = !isRunning && !preparation.isPreparing,
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(stringResource(R.string.button_select_mod_folder))
                            }
                        }
                        when {
                            preparation.isPreparing -> {
                                Text(
                                    text = preparingLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                            }
                            selectedModFolderName != null -> {
                                StatusRow(
                                    text = when {
                                        preparation.error != null -> "$errorPrefix ${preparation.error}"
                                        preparation.isReady -> "$readyLabel ${preparation.preparedCount}"
                                        else -> emptyLabel
                                    },
                                    color = if (preparation.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    enabled = !isRunning,
                                    onRefresh = {
                                        scope.launch { ModFilesRepository.prepareFiles(context) }
                                    }
                                )
                            }
                        }
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Действия")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                enabled = !isRunning && !preparation.isPreparing,
                                onClick = {
                                    val cleanupReason = VpnLogRepository.cleanupDeleteReason()
                                    cleanupReason?.let { reason ->
                                        VpnLogRepository.log("UI cleanup queued reason=${reason.code} ${reason.name}")
                                    }
                                    if (VpnLogRepository.captureSettingsNow().autoLaunchPackage == null) {
                                        pendingManualLaunchHint = PendingManualLaunchHint(
                                            cleanupMode = true,
                                            cleanupReason = cleanupReason
                                        )
                                    } else {
                                        requestStart(cleanupMode = true, cleanupReason = cleanupReason)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.button_remove_mod),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.button_remove_mod),
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                enabled = isRunning || (isInstallEnabled && !preparation.isPreparing),
                                onClick = {
                                    if (isRunning) {
                                        stopMonitoring(context)
                                    } else {
                                        if (VpnLogRepository.captureSettingsNow().autoLaunchPackage == null) {
                                            pendingManualLaunchHint = PendingManualLaunchHint(
                                                cleanupMode = false,
                                                cleanupReason = null
                                            )
                                        } else {
                                            requestStart(cleanupMode = false, cleanupReason = null)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.Download,
                                    contentDescription = if (isRunning) stopLabel else installLabel,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isRunning) stopLabel else installLabel,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

private data class PendingManualLaunchHint(
    val cleanupMode: Boolean,
    val cleanupReason: CleanupReasonSpec?
)

@Composable
private fun StatusRow(
    text: String,
    enabled: Boolean,
    onRefresh: () -> Unit,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
        IconButton(
            enabled = enabled,
            onClick = onRefresh,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.button_refresh_mod_files),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    contentPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(24.dp)
            )
            .padding(contentPadding)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun QuickInfoChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoPill(text: String, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                }
            )
            .border(
                1.dp,
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GuidanceStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsGearButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.title_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsDialog(
    isRunning: Boolean,
    isAutoVpnDisableEnabled: Boolean,
    isInstallResultNotificationsEnabled: Boolean,
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
    onInstallResultNotificationsChange: (Boolean) -> Unit,
    onFetchLatest: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                SectionTitle("Поведение")
                SettingsSwitchRow(
                    label = stringResource(R.string.label_install_result_notifications),
                    checked = isInstallResultNotificationsEnabled,
                    onCheckedChange = onInstallResultNotificationsChange
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.label_auto_disable_vpn),
                    checked = isAutoVpnDisableEnabled,
                    onCheckedChange = { VpnLogRepository.setAutoVpnDisableEnabled(context, it) }
                )
                Text(
                    text = stringResource(R.string.hint_auto_disable_vpn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.label_ip_filter),
                    checked = isIpFilterEnabled,
                    onCheckedChange = { VpnLogRepository.setIpFilterEnabled(context, it) },
                    enabled = !isRunning
                )
                if (isIpFilterEnabled) {
                    ArrayTextField(
                        value = ipFilterText,
                        onValueChange = { VpnLogRepository.setIpFilterText(context, it) },
                        enabled = !isRunning,
                        label = stringResource(R.string.label_ip_hosts)
                    )
                } else {
                    ArrayTextField(
                        value = packageText,
                        onValueChange = { VpnLogRepository.setPackageText(context, it) },
                        enabled = !isRunning,
                        label = stringResource(R.string.label_package_names)
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { VpnLogRepository.setPortText(context, it) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning,
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                SectionTitle("Автозапуск")
                if (launchableApps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.label_auto_launch_app),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                VpnLogRepository.setAutoLaunchPackage(context, null)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = autoLaunchPackage == null,
                            onClick = { VpnLogRepository.setAutoLaunchPackage(context, null) }
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
                                    VpnLogRepository.setAutoLaunchPackage(context, app.packageName)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoLaunchPackage == app.packageName,
                                onClick = { VpnLogRepository.setAutoLaunchPackage(context, app.packageName) }
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
                SectionTitle("Fingerprint")
                LabeledValue(
                    label = latestFingerprintLabel,
                    value = latestFingerprintState.savedRootSha ?: latestFingerprintNotLoaded
                )
                LabeledValue(
                    label = latestGameServerLabel,
                    value = latestFingerprintState.savedGameServer ?: latestGameServerNotLoaded
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
