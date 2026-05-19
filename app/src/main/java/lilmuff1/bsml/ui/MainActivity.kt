package lilmuff1.bsml.ui

import android.app.Activity.RESULT_OK
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.content.pm.PackageManager
import android.widget.TextView
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.DoNotDisturbAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
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
import lilmuff1.bsml.state.ImportedModRepository
import lilmuff1.bsml.state.ImportedModFeatureSelection
import lilmuff1.bsml.state.LatestFingerprintRepository
import lilmuff1.bsml.state.LatestFingerprintStore
import lilmuff1.bsml.state.ModFilesRepository
import lilmuff1.bsml.state.OriginalAssetsRepository
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.ui.theme.BSMLTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val showReinstallWarningAfterDelete by VpnLogRepository.showReinstallWarningAfterDelete.collectAsState()
    val isAutoPrepareFilesEnabled by VpnLogRepository.isAutoPrepareFilesEnabled.collectAsState()
    val autoPrepareFilesDelaySeconds by VpnLogRepository.autoPrepareFilesDelaySeconds.collectAsState()
    val isIpFilterEnabled by VpnLogRepository.isIpFilterEnabled.collectAsState()
    val ipFilterText by VpnLogRepository.ipFilterText.collectAsState()
    val packageText by VpnLogRepository.packageText.collectAsState()
    val autoLaunchPackage by VpnLogRepository.autoLaunchPackage.collectAsState()
    val portText by VpnLogRepository.portText.collectAsState()
    val preparation by ModFilesRepository.preparation.collectAsState()
    val importedModState by ImportedModRepository.state.collectAsState()
    val originalAssetsState by OriginalAssetsRepository.state.collectAsState()
    val latestFingerprintState by LatestFingerprintRepository.state.collectAsState()
    val isRunning = isVpnRunning || isAssetProxyRunning
    val scope = rememberCoroutineScope()
    var pendingCleanupMode by remember { mutableStateOf(false) }
    var pendingCleanupReason by remember { mutableStateOf<CleanupReasonSpec?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showModFeatures by remember { mutableStateOf(false) }
    var pendingManualLaunchHint by remember { mutableStateOf<PendingManualLaunchHint?>(null) }
    var showReinstallAfterDeleteWarning by remember { mutableStateOf(false) }
    var showImportedModDeleteConfirm by remember { mutableStateOf(false) }
    val modFolderNotSelected = stringResource(R.string.value_mod_folder_not_selected)
    val stopLabel = stringResource(R.string.button_stop)
    val installLabel = stringResource(R.string.button_install_mod)
    val enableModLabel = stringResource(R.string.button_enable_mod)
    val disableModLabel = stringResource(R.string.button_disable_mod)
    val refreshLabel = stringResource(R.string.button_refresh_mod_files)
    val preparingLabel = stringResource(R.string.status_mod_preparing)
    val readyLabel = stringResource(R.string.status_mod_ready)
    val emptyLabel = stringResource(R.string.status_mod_not_prepared)
    val errorPrefix = stringResource(R.string.status_mod_error_prefix)
    val fingerprintRequiredStatus = stringResource(R.string.status_fingerprint_required_for_prepare)
    val latestFingerprintLabel = stringResource(R.string.label_latest_fingerprint)
    val latestFingerprintNotLoaded = stringResource(R.string.value_latest_fingerprint_not_loaded)
    val latestGameServerLabel = stringResource(R.string.label_latest_game_server)
    val latestGameServerNotLoaded = stringResource(R.string.value_latest_game_server_not_loaded)
    val vpnPermissionDeniedStatus = stringResource(R.string.status_vpn_permission_denied)
    val vpnPermissionDeniedLog = stringResource(R.string.log_vpn_permission_denied)
    val featureConflictToast = stringResource(R.string.toast_feature_conflict)
    val invalidModArchiveToast = stringResource(R.string.toast_invalid_mod_archive)
    val selectedModFolderName = preparation.folderName
    val isInstallEnabled = !isRunning && selectedModFolderName != null && preparation.isReady && !preparation.isPreparing
    val modContentAlpha = if (selectedModFolderName != null && !importedModState.isEnabled) 0.48f else 1f
    val importedIconBitmap = remember(importedModState.fileName, importedModState.metadata, importedModState.iconLastModified) {
        ImportedModRepository.activeIconFile(context)
            .takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            ImportedModRepository.refreshState(context)
            OriginalAssetsRepository.refreshState(context)
            ModFilesRepository.refreshState(context)
            LatestFingerprintRepository.refreshState(context)
            LatestFingerprintRepository.fetchLatest(context)
        }
    }

    LaunchedEffect(
        importedModState.fileName,
        importedModState.isEnabled,
        importedModState.featureSelection.enabledFeatureIds,
        isAutoPrepareFilesEnabled,
        autoPrepareFilesDelaySeconds,
        latestFingerprintState.savedRootSha
    ) {
        if (
            isAutoPrepareFilesEnabled &&
            importedModState.fileName != null &&
            latestFingerprintState.savedRootSha != null &&
            !isRunning
        ) {
            delay(VpnLogRepository.autoPrepareFilesDelayMillisNow())
            val currentPreparation = ModFilesRepository.preparation.value
            if (!currentPreparation.isPreparing && !currentPreparation.isReady) {
                ModFilesRepository.prepareFiles(context)
            }
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
            VpnLogRepository.setStatus(vpnPermissionDeniedStatus)
            VpnLogRepository.log(vpnPermissionDeniedLog)
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
    val sourceFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching { OriginalAssetsRepository.takePersistablePermission(context, uri) }
            OriginalAssetsRepository.setTreeUri(context, uri)
        }
    }
    val modImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    ImportedModRepository.importMod(context, uri)
                }
                if (imported) {
                    if (LatestFingerprintStore.readStoredClientHelloHash(context.filesDir).isNullOrBlank()) {
                        LatestFingerprintRepository.fetchLatest(context)
                    }
                    ModFilesRepository.prepareFiles(context)
                } else {
                    Toast.makeText(context, invalidModArchiveToast, Toast.LENGTH_SHORT).show()
                    ModFilesRepository.refreshState(context)
                }
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
            showReinstallWarningAfterDelete = showReinstallWarningAfterDelete,
            isAutoPrepareFilesEnabled = isAutoPrepareFilesEnabled,
            autoPrepareFilesDelaySeconds = autoPrepareFilesDelaySeconds,
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
            originalAssetsFolderName = originalAssetsState.folderName,
            onDismiss = { showSettings = false },
            onSelectOriginalAssetsFolder = { sourceFolderPickerLauncher.launch(null) },
            onClearOriginalAssetsFolder = { OriginalAssetsRepository.setTreeUri(context, null) },
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
            onShowReinstallWarningAfterDeleteChange = { enabled ->
                VpnLogRepository.setShowReinstallWarningAfterDelete(context, enabled)
            },
            onAutoPrepareFilesEnabledChange = { enabled ->
                VpnLogRepository.setAutoPrepareFilesEnabled(context, enabled)
            },
            onAutoPrepareFilesDelaySecondsChange = { value ->
                VpnLogRepository.setAutoPrepareFilesDelaySeconds(context, value)
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

    if (showReinstallAfterDeleteWarning) {
        AlertDialog(
            onDismissRequest = { showReinstallAfterDeleteWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReinstallAfterDeleteWarning = false
                        if (VpnLogRepository.captureSettingsNow().autoLaunchPackage == null) {
                            pendingManualLaunchHint = PendingManualLaunchHint(
                                cleanupMode = false,
                                cleanupReason = null
                            )
                        } else {
                            requestStart(cleanupMode = false, cleanupReason = null)
                        }
                    }
                ) {
                    Text(stringResource(R.string.button_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReinstallAfterDeleteWarning = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            },
            title = {
                Text(stringResource(R.string.title_reinstall_after_delete_warning))
            },
            text = {
                Text(
                    text = stringResource(R.string.message_reinstall_after_delete_warning),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    if (showImportedModDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showImportedModDeleteConfirm = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportedModDeleteConfirm = false
                        ImportedModRepository.clear(context)
                        ModFilesRepository.refreshState(context)
                    }
                ) {
                    Text(stringResource(R.string.button_remove_mod))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportedModDeleteConfirm = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            },
            title = { Text(stringResource(R.string.title_delete_imported_mod_confirm)) },
            text = {
                Text(
                    text = stringResource(R.string.message_delete_imported_mod_confirm),
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
                        SectionTitle(stringResource(R.string.title_guidance))
                        GuidanceStep(number = 1, text = stringResource(R.string.guidance_step_select_mod))
                        GuidanceStep(number = 2, text = stringResource(R.string.guidance_step_action))
                        GuidanceStep(
                            number = 3,
                            text = if (autoLaunchPackage != null) {
                                stringResource(R.string.guidance_step_auto_launch)
                            } else {
                                stringResource(R.string.guidance_step_manual_launch)
                            }
                        )
                        GuidanceStep(number = 4, text = stringResource(R.string.guidance_step_cleanup_on_problem))
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Мод")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (importedIconBitmap != null) {
                                Image(
                                    bitmap = importedIconBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val title = importedModState.metadata?.title ?: selectedModFolderName ?: modFolderNotSelected
                                    HtmlText(
                                        html = title,
                                        textSizeSp = 20f,
                                        color = MaterialTheme.colorScheme.onSurface.toArgb(),
                                        modifier = Modifier.weight(1f)
                                    )
                                    importedModState.metadata?.version?.let { version ->
                                        VersionBadge(text = version)
                                    }
                                }
                                importedModState.metadata?.author?.let { author ->
                                    HtmlText(
                                        html = author,
                                        textSizeSp = 14f,
                                        color = MaterialTheme.colorScheme.primary.toArgb(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                importedModState.metadata?.description?.let { description ->
                                    HtmlText(
                                        html = description,
                                        textSizeSp = 14f,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        if (selectedModFolderName == null) {
                            Button(
                                enabled = !isRunning && !preparation.isPreparing,
                                onClick = { modImportLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = stringResource(R.string.button_import_mod),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.button_import_mod), fontSize = 16.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (preparation.isPreparing) {
                                        preparingLabel
                                    } else {
                                        when {
                                            preparation.error == "fingerprint_not_loaded" -> fingerprintRequiredStatus
                                            preparation.error != null -> "$errorPrefix ${preparation.error}"
                                            preparation.isReady -> "$readyLabel ${preparation.preparedCount}"
                                            else -> emptyLabel
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (preparation.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (importedModState.isEnabled) {
                                    IconButton(
                                        enabled = !isRunning && !preparation.isPreparing,
                                        onClick = { scope.launch { ModFilesRepository.prepareFiles(context) } },
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
                            if (preparation.isPreparing) {
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    enabled = !isRunning && !preparation.isPreparing,
                                    onClick = { ImportedModRepository.setModEnabled(context, !importedModState.isEnabled) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PowerSettingsNew,
                                        contentDescription = if (importedModState.isEnabled) disableModLabel else enableModLabel,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (importedModState.isEnabled) disableModLabel else enableModLabel, fontSize = 16.sp)
                                }
                                if (importedModState.isEnabled) {
                                    Button(
                                        enabled = !isRunning && !preparation.isPreparing,
                                        onClick = { showImportedModDeleteConfirm = true },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                            contentColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.button_remove_mod),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.button_remove_mod), fontSize = 14.sp)
                                    }
                                }
                                if (importedModState.isEnabled) {
                                    IconButton(
                                        enabled = importedModState.features.isNotEmpty() && !preparation.isPreparing && !isRunning,
                                        onClick = { showModFeatures = !showModFeatures },
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            if (showModFeatures && importedModState.features.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                FeatureSelectionSection(
                                    features = importedModState.features,
                                    groups = importedModState.featureGroups,
                                    selectedIds = importedModState.featureSelection.enabledFeatureIds,
                                    enabled = !preparation.isPreparing && !isRunning,
                                    onSelectionChange = { selectedIds, preferredFeatureId ->
                                        val conflict = ImportedModRepository.updateFeatureSelection(
                                            context,
                                            ImportedModFeatureSelection(enabledFeatureIds = selectedIds),
                                            preferredFeatureId = preferredFeatureId
                                        )
                                        if (conflict != null) {
                                            Toast.makeText(
                                                context,
                                                featureConflictToast.format(conflict.featureName, conflict.conflictName),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.title_actions))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                enabled = !isRunning && !preparation.isPreparing,
                                onClick = {
                                    if (VpnLogRepository.captureSettingsNow().autoLaunchPackage == null) {
                                        pendingManualLaunchHint = PendingManualLaunchHint(
                                            cleanupMode = true,
                                            cleanupReason = null
                                        )
                                    } else {
                                        requestStart(cleanupMode = true, cleanupReason = null)
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
                                        if (
                                            VpnLogRepository.isDeleteCleanupPendingNow() &&
                                            VpnLogRepository.shouldShowReinstallWarningAfterDeleteNow()
                                        ) {
                                            showReinstallAfterDeleteWarning = true
                                        } else if (VpnLogRepository.captureSettingsNow().autoLaunchPackage == null) {
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
private fun VersionBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HtmlText(
    html: String,
    textSizeSp: Float,
    color: Int,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                includeFontPadding = false
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            view.textSize = textSizeSp
            view.setTextColor(color)
            view.setLinkTextColor(android.graphics.Color.parseColor("#64B5F6"))
            view.linksClickable = true
            view.movementMethod = LinkMovementMethod.getInstance()
        }
    )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
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
private fun FeatureSelectionSection(
    features: List<lilmuff1.bsml.state.ImportedModFeature>,
    groups: List<lilmuff1.bsml.state.ImportedModFeatureGroup>,
    selectedIds: Set<String>,
    enabled: Boolean,
    onSelectionChange: (Set<String>, String?) -> Unit
) {
    val featureMap = features.associateBy { it.id }
    val groupedIds = groups.flatMap { it.features }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.title_features))
        groups.forEach { group ->
            if (group.type == "RADIO_GROUP") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) {
                            val updated = selectedIds.toMutableSet().apply {
                                removeAll(group.features.toSet())
                            }
                            onSelectionChange(updated, null)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.name ?: group.id,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        enabled = enabled,
                        onClick = {
                            val updated = selectedIds.toMutableSet().apply {
                                removeAll(group.features.toSet())
                            }
                            onSelectionChange(updated, null)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DoNotDisturbAlt,
                            contentDescription = stringResource(R.string.option_no_feature),
                            tint = if (group.features.none { it in selectedIds }) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            } else {
                Text(
                    text = group.name ?: group.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            group.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (group.type == "RADIO_GROUP") {
                group.features.forEach { featureId ->
                    val feature = featureMap[featureId] ?: return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                val updated = selectedIds.toMutableSet().apply {
                                    removeAll(group.features.toSet())
                                    add(featureId)
                                }
                                onSelectionChange(updated, featureId)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(feature.name ?: feature.id, style = MaterialTheme.typography.bodyMedium)
                            feature.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        RadioButton(
                            selected = featureId in selectedIds,
                            enabled = enabled,
                            onClick = {
                                val updated = selectedIds.toMutableSet().apply {
                                    removeAll(group.features.toSet())
                                    add(featureId)
                                }
                                onSelectionChange(updated, featureId)
                            }
                        )
                    }
                }
            } else {
                group.features.forEach { featureId ->
                    val feature = featureMap[featureId] ?: return@forEach
                    SettingsSwitchRow(
                        label = feature.name ?: feature.id,
                        checked = featureId in selectedIds,
                        enabled = enabled,
                        onCheckedChange = { enabled ->
                            val updated = selectedIds.toMutableSet()
                            if (enabled) updated += featureId else updated -= featureId
                            onSelectionChange(updated, if (enabled) featureId else null)
                        }
                    )
                }
            }
        }
        features.filter { it.id !in groupedIds }.forEach { feature ->
            SettingsSwitchRow(
                label = feature.name ?: feature.id,
                checked = feature.id in selectedIds,
                enabled = enabled,
                onCheckedChange = { enabled ->
                    val updated = selectedIds.toMutableSet()
                    if (enabled) updated += feature.id else updated -= feature.id
                    onSelectionChange(updated, if (enabled) feature.id else null)
                }
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    isRunning: Boolean,
    isAutoVpnDisableEnabled: Boolean,
    isInstallResultNotificationsEnabled: Boolean,
    showReinstallWarningAfterDelete: Boolean,
    isAutoPrepareFilesEnabled: Boolean,
    autoPrepareFilesDelaySeconds: String,
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
    originalAssetsFolderName: String?,
    onDismiss: () -> Unit,
    onSelectOriginalAssetsFolder: () -> Unit,
    onClearOriginalAssetsFolder: () -> Unit,
    onInstallResultNotificationsChange: (Boolean) -> Unit,
    onShowReinstallWarningAfterDeleteChange: (Boolean) -> Unit,
    onAutoPrepareFilesEnabledChange: (Boolean) -> Unit,
    onAutoPrepareFilesDelaySecondsChange: (String) -> Unit,
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
                SectionTitle(stringResource(R.string.title_behavior))
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
                SectionTitle(stringResource(R.string.title_original_assets))
                LabeledValue(
                    label = stringResource(R.string.label_original_assets_folder),
                    value = originalAssetsFolderName ?: stringResource(R.string.value_mod_folder_not_selected)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !isRunning,
                        onClick = onSelectOriginalAssetsFolder,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.button_select_original_assets_folder))
                    }
                    if (originalAssetsFolderName != null) {
                        TextButton(
                            enabled = !isRunning,
                            onClick = onClearOriginalAssetsFolder
                        ) {
                            Text(stringResource(R.string.button_clear))
                        }
                    }
                }
                SectionTitle(stringResource(R.string.title_auto_launch))
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
                SectionTitle(stringResource(R.string.title_fingerprint))
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
                SectionTitle(stringResource(R.string.title_misc))
                SettingsSwitchRow(
                    label = stringResource(R.string.label_reinstall_after_delete_warning),
                    checked = showReinstallWarningAfterDelete,
                    onCheckedChange = onShowReinstallWarningAfterDeleteChange
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.label_auto_prepare_files),
                    checked = isAutoPrepareFilesEnabled,
                    onCheckedChange = onAutoPrepareFilesEnabledChange,
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = autoPrepareFilesDelaySeconds,
                    onValueChange = onAutoPrepareFilesDelaySecondsChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning && isAutoPrepareFilesEnabled,
                    label = { Text(stringResource(R.string.label_auto_prepare_files_delay_seconds)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedButton(
                    onClick = { exportBsmlLogs(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_export_logs))
                }
                SectionTitle(stringResource(R.string.title_author_links))
                AuthorLinkRow(
                    label = stringResource(R.string.label_author_telegram),
                    url = "https://t.me/lilmuff1"
                )
                AuthorLinkRow(
                    label = stringResource(R.string.label_author_discord),
                    url = "https://discord.com/users/lilmuff1"
                )
                AuthorLinkRow(
                    label = stringResource(R.string.label_author_github),
                    url = "https://github.com/lilmuff2"
                )
                AuthorLinkRow(
                    label = stringResource(R.string.label_author_website),
                    url = "https://lilmuff1.xyz"
                )
            }
        }
    )
}

@Composable
private fun AuthorLinkRow(label: String, url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openExternalUrl(context, url) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = url.removePrefix("https://"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
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

private fun exportBsmlLogs(context: Context) {
    val logs = VpnLogRepository.exportLogsText().ifBlank { "BSMLLocalVpn logs are empty" }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "BSMLLocalVpn logs")
        putExtra(Intent.EXTRA_TEXT, logs)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.button_export_logs)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
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
