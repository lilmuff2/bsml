package lilmuff1.bsml.ui

import android.app.Activity.RESULT_OK
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
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
import lilmuff1.bsml.state.UpdateInfo
import lilmuff1.bsml.state.UpdateRepository
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

private const val ORIGINAL_GAME_PACKAGE = "com.supercell.brawlstars"

private fun wrapContextWithLocale(context: Context, languageTag: String): Context {
    val locale = if (languageTag == "system") {
        val systemConfig = android.content.res.Resources.getSystem().configuration
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            systemConfig.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            systemConfig.locale
        }
    } else {
        java.util.Locale.forLanguageTag(languageTag)
    }
    java.util.Locale.setDefault(locale)
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("bsml_vpn_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "system") ?: "system"
        super.attachBaseContext(wrapContextWithLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnLogRepository.initialize(this)
        enableEdgeToEdge()
        setContent {
            BSMLTheme {
                MainScreen()
            }
        }
        handleIncomingModIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingModIntent(intent)
    }

    private fun handleIncomingModIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW) return
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                ImportedModRepository.importMod(this@MainActivity, uri)
            }
            if (imported.success) {
                if (LatestFingerprintStore.readStoredClientHelloHash(filesDir).isNullOrBlank()) {
                    LatestFingerprintRepository.fetchLatest(this@MainActivity)
                }
                Toast.makeText(this@MainActivity, getString(R.string.toast_mod_imported_from_file), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.toast_invalid_mod_archive), Toast.LENGTH_SHORT).show()
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
    val appLanguage by VpnLogRepository.appLanguage.collectAsState()
    val showReinstallWarningAfterDelete by VpnLogRepository.showReinstallWarningAfterDelete.collectAsState()
    val isThoroughModDeleteEnabled by VpnLogRepository.isThoroughModDeleteEnabled.collectAsState()
    val isAutoPrepareFilesEnabled by VpnLogRepository.isAutoPrepareFilesEnabled.collectAsState()
    val autoPrepareFilesDelaySeconds by VpnLogRepository.autoPrepareFilesDelaySeconds.collectAsState()
    val exportLogLines by VpnLogRepository.exportLogLines.collectAsState()
    val isIpFilterEnabled by VpnLogRepository.isIpFilterEnabled.collectAsState()
    val ipFilterText by VpnLogRepository.ipFilterText.collectAsState()
    val packageText by VpnLogRepository.packageText.collectAsState()
    val autoLaunchPackage by VpnLogRepository.autoLaunchPackage.collectAsState()
    val portText by VpnLogRepository.portText.collectAsState()
    val preparation by ModFilesRepository.preparation.collectAsState()
    val importedModState by ImportedModRepository.state.collectAsState()
    val originalAssetsState by OriginalAssetsRepository.state.collectAsState()
    val latestFingerprintState by LatestFingerprintRepository.state.collectAsState()
    val updateState by UpdateRepository.state.collectAsState()
    val vpnStartBlockedSignal by VpnLogRepository.vpnStartBlockedSignal.collectAsState()
    val isRunning = isVpnRunning || isAssetProxyRunning
    val scope = rememberCoroutineScope()
    var pendingCleanupMode by remember { mutableStateOf(false) }
    var pendingCleanupReason by remember { mutableStateOf<CleanupReasonSpec?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var expandedFeaturesModId by remember { mutableStateOf<String?>(null) }
    var pendingManualLaunchHint by remember { mutableStateOf<PendingManualLaunchHint?>(null) }
    var showReinstallAfterDeleteWarning by remember { mutableStateOf(false) }
    var showImportedModDeleteConfirm by remember { mutableStateOf(false) }
    var showVpnStartBlockedDialog by remember { mutableStateOf(false) }
    var handledVpnStartBlockedSignal by remember { mutableStateOf(0) }
    var skippedUpdateVersionCode by remember { mutableStateOf<Long?>(null) }
    var autoPrepareRequestId by remember { mutableStateOf(0) }
    val stopLabel = stringResource(R.string.button_stop)
    val installLabel = stringResource(R.string.button_install_mod)
    val enableModLabel = stringResource(R.string.button_enable_mod)
    val disableModLabel = stringResource(R.string.button_disable_mod)
    val refreshLabel = stringResource(R.string.button_refresh_mod_files)
    val preparingLabel = stringResource(R.string.status_mod_preparing)
    val readyLabel = stringResource(R.string.status_mod_ready)
    val emptyLabel = stringResource(R.string.status_mod_not_prepared)
    val errorPrefix = stringResource(R.string.status_mod_error_prefix)
    val preparationStageLabel = when (preparation.stage) {
        ModFilesRepository.STAGE_ARCHIVE -> stringResource(R.string.prepare_stage_archive)
        ModFilesRepository.STAGE_CSV -> stringResource(R.string.prepare_stage_csv)
        ModFilesRepository.STAGE_ORIGINAL_ASSETS -> stringResource(R.string.prepare_stage_original_assets)
        ModFilesRepository.STAGE_SAVING -> stringResource(R.string.prepare_stage_saving)
        else -> preparingLabel
    }
    val preparationStatusText = if (preparation.isPreparing) {
        if (preparation.totalCount > 0) {
            "$preparationStageLabel ${preparation.preparedCount}/${preparation.totalCount}"
        } else {
            preparationStageLabel
        }
    } else {
        null
    }
    val fingerprintRequiredStatus = stringResource(R.string.status_fingerprint_required_for_prepare)
    val latestFingerprintLabel = stringResource(R.string.label_latest_fingerprint)
    val latestFingerprintNotLoaded = stringResource(R.string.value_latest_fingerprint_not_loaded)
    val latestGameServerLabel = stringResource(R.string.label_latest_game_server)
    val latestGameServerNotLoaded = stringResource(R.string.value_latest_game_server_not_loaded)
    val vpnPermissionDeniedStatus = stringResource(R.string.status_vpn_permission_denied)
    val vpnPermissionDeniedLog = stringResource(R.string.log_vpn_permission_denied)
    val featureConflictToast = stringResource(R.string.toast_feature_conflict)
    val invalidModArchiveToast = stringResource(R.string.toast_invalid_mod_archive)
    val modImportedToast = stringResource(R.string.toast_mod_imported_from_file)
    val updateOpenFailedToast = stringResource(R.string.toast_update_open_failed)
    val updateToShow = updateState.update?.takeIf { update ->
        update.required || skippedUpdateVersionCode != update.versionCode
    }
    val hasImportedMods = importedModState.mods.isNotEmpty()
    val hasOriginalAssetsFolder = originalAssetsState.folderName != null
    val canPrepareModFiles = hasImportedMods || hasOriginalAssetsFolder
    val isInstallEnabled = !isRunning &&
        !preparation.isPreparing &&
        preparation.isReady
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
        }
        launch {
            UpdateRepository.checkForUpdates(context)
        }
        launch {
            LatestFingerprintRepository.fetchLatest(context)
        }
    }

    LaunchedEffect(vpnStartBlockedSignal) {
        if (vpnStartBlockedSignal != 0 && vpnStartBlockedSignal != handledVpnStartBlockedSignal) {
            handledVpnStartBlockedSignal = vpnStartBlockedSignal
            showVpnStartBlockedDialog = true
        }
    }

    LaunchedEffect(appLanguage) {
        withContext(Dispatchers.IO) {
            ImportedModRepository.refreshState(context)
            ModFilesRepository.refreshPreparedStateFromImportedMod(context)
        }
    }

    fun requestAutoPrepare() {
        autoPrepareRequestId++
    }

    LaunchedEffect(
        autoPrepareRequestId,
        isAutoPrepareFilesEnabled,
        autoPrepareFilesDelaySeconds,
        latestFingerprintState.savedRootSha,
        canPrepareModFiles,
        isRunning
    ) {
        if (
            (autoPrepareRequestId > 0 || canPrepareModFiles) &&
            isAutoPrepareFilesEnabled &&
            canPrepareModFiles &&
            latestFingerprintState.savedRootSha != null &&
            !isRunning
        ) {
            delay(VpnLogRepository.autoPrepareFilesDelayMillisNow())
            val currentPreparation = ModFilesRepository.preparation.value
            if (!currentPreparation.isPreparing) {
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
            VpnLogRepository.notifyVpnStartBlocked("VPN permission result was not OK")
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
                if (imported.success) {
                    if (LatestFingerprintStore.readStoredClientHelloHash(context.filesDir).isNullOrBlank()) {
                        LatestFingerprintRepository.fetchLatest(context)
                    }
                    Toast.makeText(context, modImportedToast, Toast.LENGTH_SHORT).show()
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
            runCatching {
                vpnPermissionLauncher.launch(permissionIntent)
            }.getOrElse { error ->
                VpnLogRepository.notifyVpnStartBlocked(
                    "VPN permission activity failed: ${error::class.java.simpleName}"
                )
            }
        }
    }

    fun requestInstallStart() {
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

    if (showSettings) {
        SettingsScreen(
            isRunning = isRunning,
            isAutoVpnDisableEnabled = isAutoVpnDisableEnabled,
            isInstallResultNotificationsEnabled = isInstallResultNotificationsEnabled,
            showReinstallWarningAfterDelete = showReinstallWarningAfterDelete,
            isThoroughModDeleteEnabled = isThoroughModDeleteEnabled,
            isAutoPrepareFilesEnabled = isAutoPrepareFilesEnabled,
            autoPrepareFilesDelaySeconds = autoPrepareFilesDelaySeconds,
            exportLogLines = exportLogLines,
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
            onThoroughModDeleteChange = { enabled ->
                VpnLogRepository.setThoroughModDeleteEnabled(context, enabled)
            },
            onAutoPrepareFilesEnabledChange = { enabled ->
                VpnLogRepository.setAutoPrepareFilesEnabled(context, enabled)
            },
            onAutoPrepareFilesDelaySecondsChange = { value ->
                VpnLogRepository.setAutoPrepareFilesDelaySeconds(context, value)
            },
            onExportLogLinesChange = { value ->
                VpnLogRepository.setExportLogLines(context, value)
            },
            onFetchLatest = {
                scope.launch {
                    LatestFingerprintRepository.fetchLatest(context)
                }
            }
        )
        return
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
                        requestAutoPrepare()
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

    if (showVpnStartBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showVpnStartBlockedDialog = false },
            confirmButton = {
                TextButton(onClick = { showVpnStartBlockedDialog = false }) {
                    Text(stringResource(R.string.button_done))
                }
            },
            title = { Text(stringResource(R.string.title_vpn_start_blocked)) },
            text = {
                Text(
                    text = stringResource(R.string.message_vpn_start_blocked),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    updateToShow?.let { update ->
        UpdateDialog(
            update = update,
            onDownload = {
                openUpdateUrl(context, update.apkUrl, updateOpenFailedToast)
            },
            onSkip = {
                skippedUpdateVersionCode = update.versionCode
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
                                enabled = true,
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
                        SectionTitle("Моды")
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

                        if (canPrepareModFiles) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (preparation.isPreparing) {
                                        preparationStatusText ?: preparingLabel
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
                            if (preparation.isPreparing) {
                                if (preparation.totalCount > 0) {
                                    CleanLinearProgressIndicator(progress = preparation.progress, modifier = Modifier.fillMaxWidth())
                                } else {
                                    CleanIndeterminateProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }

                        if (hasImportedMods) {
                            importedModState.mods.forEach { mod ->
                                ImportedModListCard(
                                    context = context,
                                    mod = mod,
                                    isRunning = isRunning,
                                    isPreparing = preparation.isPreparing,
                                    isSelectedForDetails = mod.id == importedModState.selectedModId,
                                    showFeatures = expandedFeaturesModId == mod.id,
                                    onSelect = { ImportedModRepository.selectMod(context, mod.id) },
                                    onMoveUp = {
                                        ImportedModRepository.moveModUp(context, mod.id)
                                        requestAutoPrepare()
                                    },
                                    onMoveDown = {
                                        ImportedModRepository.moveModDown(context, mod.id)
                                        requestAutoPrepare()
                                    },
                                    onToggleFeatures = {
                                        expandedFeaturesModId = if (expandedFeaturesModId == mod.id) null else mod.id
                                    },
                                    onToggleEnabled = {
                                        ImportedModRepository.setModEnabled(context, mod.id, !mod.isEnabled)
                                        requestAutoPrepare()
                                    },
                                    onDelete = {
                                        ImportedModRepository.selectMod(context, mod.id)
                                        showImportedModDeleteConfirm = true
                                    },
                                    onFeatureSelectionChange = { selectedIds, preferredFeatureId ->
                                        val conflict = ImportedModRepository.updateFeatureSelection(
                                            context,
                                            mod.id,
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
                                        requestAutoPrepare()
                                    },
                                    enableModLabel = enableModLabel,
                                    disableModLabel = disableModLabel,
                                    currentGameVersion = latestFingerprintState.savedGameVersion,
                                    iconBitmap = ImportedModRepository.iconFile(context, mod.id)
                                        .takeIf { it.isFile }
                                        ?.let { BitmapFactory.decodeFile(it.absolutePath) }
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
                                enabled = isRunning || isInstallEnabled,
                                onClick = {
                                    if (isRunning) {
                                        stopMonitoring(context)
                                    } else {
                                        requestInstallStart()
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
private fun UpdateDialog(
    update: UpdateInfo,
    onDownload: () -> Unit,
    onSkip: () -> Unit
) {
    val versionLabel = update.versionName ?: update.versionCode.toString()
    AlertDialog(
        onDismissRequest = {
            if (!update.required) onSkip()
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.button_download_update))
            }
        },
        dismissButton = if (update.required) {
            null
        } else {
            {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.button_skip_update))
                }
            }
        },
        title = {
            Text(
                stringResource(
                    if (update.required) {
                        R.string.title_update_required
                    } else {
                        R.string.title_update_available
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        if (update.required) {
                            R.string.message_update_required
                        } else {
                            R.string.message_update_available
                        },
                        versionLabel
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                update.changelog?.takeIf { it.isNotBlank() }?.let { changelog ->
                    Text(
                        text = changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

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
private fun ImportedModListCard(
    context: Context,
    mod: lilmuff1.bsml.state.ImportedModListItem,
    isRunning: Boolean,
    isPreparing: Boolean,
    isSelectedForDetails: Boolean,
    showFeatures: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleFeatures: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onFeatureSelectionChange: (Set<String>, String?) -> Unit,
    enableModLabel: String,
    disableModLabel: String,
    currentGameVersion: Int?,
    iconBitmap: Bitmap?
) {
    val controlsEnabled = !isRunning && !isPreparing
    SectionCard(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                iconBitmap?.let { icon ->
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .alpha(if (mod.isEnabled) 1f else 0.48f)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).alpha(if (mod.isEnabled) 1f else 0.48f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HtmlText(
                            html = mod.metadata?.title ?: mod.fileName,
                            textSizeSp = 18f,
                            color = MaterialTheme.colorScheme.onSurface.toArgb(),
                            modifier = Modifier.weight(1f)
                        )
                        mod.metadata?.version?.let { VersionBadge(text = it) }
                    }
                    mod.metadata?.author?.let {
                        HtmlText(
                            html = it,
                            textSizeSp = 13f,
                            color = MaterialTheme.colorScheme.primary.toArgb(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.alpha(if (mod.isEnabled) 1f else 0.48f)
                ) {
                    IconButton(onClick = onMoveUp, enabled = controlsEnabled, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = controlsEnabled, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().alpha(if (mod.isEnabled) 1f else 0.48f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (mod.metadata?.isSignatureVerified == false) {
                    Text(
                        text = stringResource(R.string.warning_mod_unverified_signature),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                mod.metadata?.description?.let {
                    HtmlText(html = it, textSizeSp = 13f, color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(), modifier = Modifier.fillMaxWidth())
                }
                val modGameVersion = mod.metadata?.gameVersion
                if (modGameVersion != null && currentGameVersion != null && modGameVersion < currentGameVersion) {
                    Text(
                        text = stringResource(R.string.warning_mod_old_game_version, modGameVersion, currentGameVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = controlsEnabled,
                    onClick = onToggleEnabled,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mod.isEnabled) {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (mod.isEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (mod.isEnabled) disableModLabel else enableModLabel, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (mod.isEnabled) disableModLabel else enableModLabel, fontSize = 16.sp)
                }
                if (mod.isEnabled) {
                    Button(enabled = controlsEnabled, onClick = onDelete, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.button_remove_mod), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.button_remove_mod), fontSize = 14.sp)
                    }
                    IconButton(enabled = mod.features.isNotEmpty() && controlsEnabled, onClick = { onSelect(); onToggleFeatures() }, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                }
            }

            if (mod.isEnabled && showFeatures && mod.features.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                FeatureSelectionSection(
                    features = mod.features,
                    groups = mod.featureGroups,
                    selectedIds = mod.featureSelection.enabledFeatureIds,
                    enabled = controlsEnabled,
                    onSelectionChange = onFeatureSelectionChange
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    isRunning: Boolean,
    isAutoVpnDisableEnabled: Boolean,
    isInstallResultNotificationsEnabled: Boolean,
    showReinstallWarningAfterDelete: Boolean,
    isThoroughModDeleteEnabled: Boolean,
    isAutoPrepareFilesEnabled: Boolean,
    autoPrepareFilesDelaySeconds: String,
    exportLogLines: String,
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
    onThoroughModDeleteChange: (Boolean) -> Unit,
    onAutoPrepareFilesEnabledChange: (Boolean) -> Unit,
    onAutoPrepareFilesDelaySecondsChange: (String) -> Unit,
    onExportLogLinesChange: (String) -> Unit,
    onFetchLatest: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val appLanguage by VpnLogRepository.appLanguage.collectAsState()
    var languageDropdownExpanded by remember { mutableStateOf(false) }
    val languages = listOf(
        "system" to R.string.language_system,
        "en" to R.string.language_english,
        "ru" to R.string.language_russian,
        "zh" to R.string.language_chinese
    )
    val selectedLanguage = languages.firstOrNull { it.first == appLanguage } ?: languages.first()
    val selectedLanguageLabel = stringResource(selectedLanguage.second)
    fun setAutoLaunchPackageAndRefreshIfNeeded(packageName: String?) {
        val shouldRefreshFingerprint = packageName != autoLaunchPackage
        VpnLogRepository.setAutoLaunchPackage(context, packageName)
        if (shouldRefreshFingerprint) {
            scope.launch { LatestFingerprintRepository.fetchLatest(context) }
        }
    }

    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.button_done),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.title_settings),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                SectionCard(contentPadding = 16.dp) {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsSwitchRow(
                        label = stringResource(R.string.label_reinstall_after_delete_warning),
                        checked = showReinstallWarningAfterDelete,
                        onCheckedChange = onShowReinstallWarningAfterDeleteChange
                    )
                    SettingsSwitchRow(
                        label = stringResource(R.string.label_thorough_mod_delete),
                        checked = isThoroughModDeleteEnabled,
                        onCheckedChange = onThoroughModDeleteChange,
                        enabled = !isRunning
                    )
                    Text(
                        text = stringResource(R.string.hint_thorough_mod_delete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(contentPadding = 16.dp) {
                    SectionTitle(stringResource(R.string.label_auto_launch_app))
                    if (launchableApps.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    setAutoLaunchPackageAndRefreshIfNeeded(null)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
	                            RadioButton(
	                                selected = autoLaunchPackage == null,
	                                onClick = { setAutoLaunchPackageAndRefreshIfNeeded(null) }
	                            )
                            Column(
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.option_no_auto_launch),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        launchableApps.forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
	                                .clip(RoundedCornerShape(12.dp))
	                                .clickable {
	                                        setAutoLaunchPackageAndRefreshIfNeeded(app.packageName)
	                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
	                                RadioButton(
	                                    selected = autoLaunchPackage == app.packageName,
	                                    onClick = { setAutoLaunchPackageAndRefreshIfNeeded(app.packageName) }
	                                )
                                Image(
                                    bitmap = app.icon.asImageBitmap(),
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column(
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.status_auto_launch_apps_not_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SectionCard(contentPadding = 16.dp) {
                    SectionTitle("VPN")
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
                }

                SectionCard(contentPadding = 16.dp) {
                    SectionTitle(stringResource(R.string.title_original_assets))
                    if (originalAssetsFolderName == null) {
                        OutlinedButton(
                            enabled = !isRunning,
                            onClick = onSelectOriginalAssetsFolder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.button_select_original_assets_folder))
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = originalAssetsFolderName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            IconButton(enabled = !isRunning, onClick = onClearOriginalAssetsFolder) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.button_clear))
                            }
                        }
                    }
                }

                SectionCard(contentPadding = 16.dp) {
                    SectionTitle(stringResource(R.string.title_language))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { languageDropdownExpanded = true }
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedLanguageLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.title_select_language),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = languageDropdownExpanded,
                            onDismissRequest = { languageDropdownExpanded = false }
                        ) {
                            languages.forEach { (tag, labelRes) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    trailingIcon = {
                                        RadioButton(
                                            selected = appLanguage == tag,
                                            onClick = null
                                        )
                                    },
                                    onClick = {
                                        languageDropdownExpanded = false
                                        VpnLogRepository.setAppLanguage(context, tag)
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                )
                            }
                        }
                    }
                }

                SectionCard(contentPadding = 16.dp) {
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
                }

                SectionCard(contentPadding = 16.dp) {
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    }
                    OutlinedButton(
                        enabled = !isRunning && !latestFingerprintState.isFetching,
                        onClick = onFetchLatest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.button_fetch_latest_fingerprint))
                    }
                }

                SectionCard(contentPadding = 16.dp) {
                    OutlinedButton(
                        onClick = { exportBsmlLogs(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.button_export_logs))
                    }
                    OutlinedTextField(
                        value = exportLogLines,
                        onValueChange = onExportLogLinesChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning,
                        label = { Text(stringResource(R.string.label_export_log_lines)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AuthorLinkPainterIcon(painterResource(R.drawable.ic_telegram), stringResource(R.string.label_author_telegram), "https://t.me/lilmuff1")
                        AuthorLinkPainterIcon(painterResource(R.drawable.ic_discord), stringResource(R.string.label_author_discord), "https://discord.com/users/lilmuff1")
                        AuthorLinkPainterIcon(painterResource(R.drawable.ic_github), stringResource(R.string.label_author_github), "https://github.com/lilmuff2/bsml")
                        AuthorLinkIcon(Icons.Rounded.Public, stringResource(R.string.label_author_website), "https://lilmuff1.xyz")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun AuthorLinkIcon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    url: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = { openExternalUrl(context, url) }) {
        Icon(
            imageVector = imageVector,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun AuthorLinkPainterIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    url: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = { openExternalUrl(context, url) }) {
        Icon(
            painter = painter,
            contentDescription = label,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(26.dp)
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

private fun openUpdateUrl(context: Context, apkUrl: String, errorText: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.getOrElse {
        Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
    }
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
    val logs = VpnLogRepository.exportLogsText(limit = VpnLogRepository.exportLogLinesNow())
        .ifBlank { "BSMLLocalVpn logs are empty" }
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
