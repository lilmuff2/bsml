package lilmuff1.bsml.state

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import lilmuff1.bsml.config.DEFAULT_CAPTURE_PACKAGES
import lilmuff1.bsml.config.DEFAULT_CAPTURE_TARGETS
import lilmuff1.bsml.config.GAME_PORT

data class VpnCaptureSettings(
    val filterByIp: Boolean,
    val ipFilterText: String,
    val packageText: String,
    val port: Int,
    val autoLaunchPackage: String?
)

data class CleanupReasonSpec(
    val code: Int,
    val name: String
)

object VpnLogRepository {
    private const val TAG = "BSMLLocalVpn"
    private const val MAX_LOGS = 200
    private const val PREFS_NAME = "bsml_vpn_settings"
    private const val KEY_AUTO_VPN_DISABLE = "auto_vpn_disable"
    private const val KEY_AUTO_LAUNCH_GAME = "auto_launch_game"
    private const val KEY_IP_FILTER_ENABLED = "ip_filter_enabled"
    private const val KEY_IP_FILTER_TEXT = "ip_filter_text"
    private const val KEY_PACKAGE_TEXT = "package_text"
    private const val KEY_AUTO_LAUNCH_PACKAGE = "auto_launch_package"
    private const val KEY_PORT_TEXT = "port_text"
    private const val KEY_INSTALL_RESULT_NOTIFICATIONS_ENABLED = "install_result_notifications_enabled"
    private const val KEY_LAST_CLIENT_VERSION = "last_client_version"
    private const val KEY_SHOW_REINSTALL_WARNING_AFTER_DELETE = "show_reinstall_warning_after_delete"
    private const val KEY_AUTO_PREPARE_FILES_ENABLED = "auto_prepare_files_enabled"
    private const val KEY_AUTO_PREPARE_FILES_DELAY_SECONDS = "auto_prepare_files_delay_seconds"
    private val cleanupWarmupReason = CleanupReasonSpec(7, "CLIENT_CONTENT_UPDATE")

    private val logLock = Any()
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    @Volatile
    private var preferencesLoaded = false

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow("Остановлено")
    val status = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _isAssetProxyRunning = MutableStateFlow(false)
    val isAssetProxyRunning = _isAssetProxyRunning.asStateFlow()

    private val _isAutoVpnDisableEnabled = MutableStateFlow(false)
    val isAutoVpnDisableEnabled = _isAutoVpnDisableEnabled.asStateFlow()

    private val _isAutoLaunchGameEnabled = MutableStateFlow(false)
    val isAutoLaunchGameEnabled = _isAutoLaunchGameEnabled.asStateFlow()

    private val _isIpFilterEnabled = MutableStateFlow(true)
    val isIpFilterEnabled = _isIpFilterEnabled.asStateFlow()

    private val _ipFilterText = MutableStateFlow(DEFAULT_CAPTURE_TARGETS)
    val ipFilterText = _ipFilterText.asStateFlow()

    private val _packageText = MutableStateFlow(DEFAULT_CAPTURE_PACKAGES)
    val packageText = _packageText.asStateFlow()

    private val _autoLaunchPackage = MutableStateFlow<String?>(null)
    val autoLaunchPackage = _autoLaunchPackage.asStateFlow()

    private val _portText = MutableStateFlow(GAME_PORT.toString())
    val portText = _portText.asStateFlow()

    private val _isInstallResultNotificationsEnabled = MutableStateFlow(false)
    val isInstallResultNotificationsEnabled = _isInstallResultNotificationsEnabled.asStateFlow()

    private val _lastClientVersion = MutableStateFlow<String?>(null)
    val lastClientVersion = _lastClientVersion.asStateFlow()

    private val _showReinstallWarningAfterDelete = MutableStateFlow(true)
    val showReinstallWarningAfterDelete = _showReinstallWarningAfterDelete.asStateFlow()

    private val _isAutoPrepareFilesEnabled = MutableStateFlow(true)
    val isAutoPrepareFilesEnabled = _isAutoPrepareFilesEnabled.asStateFlow()

    private val _autoPrepareFilesDelaySeconds = MutableStateFlow("3")
    val autoPrepareFilesDelaySeconds = _autoPrepareFilesDelaySeconds.asStateFlow()

    private val _deleteCleanupPending = MutableStateFlow(false)
    val deleteCleanupPending = _deleteCleanupPending.asStateFlow()

    fun initialize(context: Context) {
        if (preferencesLoaded) return
        synchronized(this) {
            if (preferencesLoaded) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isAutoVpnDisableEnabled.value = prefs.getBoolean(KEY_AUTO_VPN_DISABLE, false)
            _isAutoLaunchGameEnabled.value = prefs.getBoolean(KEY_AUTO_LAUNCH_GAME, false)
            _isIpFilterEnabled.value = prefs.getBoolean(KEY_IP_FILTER_ENABLED, true)
            _ipFilterText.value = prefs.getString(KEY_IP_FILTER_TEXT, DEFAULT_CAPTURE_TARGETS) ?: DEFAULT_CAPTURE_TARGETS
            _packageText.value = prefs.getString(KEY_PACKAGE_TEXT, DEFAULT_CAPTURE_PACKAGES) ?: DEFAULT_CAPTURE_PACKAGES
            _autoLaunchPackage.value = prefs.getString(KEY_AUTO_LAUNCH_PACKAGE, null)?.trim()?.ifEmpty { null }
            _portText.value = prefs.getString(KEY_PORT_TEXT, GAME_PORT.toString())
                ?.filter { it.isDigit() }
                ?.take(5)
                ?.ifEmpty { GAME_PORT.toString() }
                ?: GAME_PORT.toString()
            _isInstallResultNotificationsEnabled.value = prefs.getBoolean(KEY_INSTALL_RESULT_NOTIFICATIONS_ENABLED, false)
            _lastClientVersion.value = prefs.getString(KEY_LAST_CLIENT_VERSION, null)?.trim()?.ifEmpty { null }
            _showReinstallWarningAfterDelete.value = prefs.getBoolean(KEY_SHOW_REINSTALL_WARNING_AFTER_DELETE, true)
            _isAutoPrepareFilesEnabled.value = prefs.getBoolean(KEY_AUTO_PREPARE_FILES_ENABLED, true)
            _autoPrepareFilesDelaySeconds.value = prefs.getInt(KEY_AUTO_PREPARE_FILES_DELAY_SECONDS, 3).toString()
            preferencesLoaded = true
        }
    }

    private fun updatePreference(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply(block)
            .commit()
    }

    fun setStatus(status: String) {
        _status.value = status
    }

    fun setRunning(isRunning: Boolean) {
        _isRunning.value = isRunning
    }

    fun isVpnRunningNow(): Boolean = _isRunning.value

    fun setAssetProxyRunning(isRunning: Boolean) {
        _isAssetProxyRunning.value = isRunning
    }

    fun setAutoVpnDisableEnabled(isEnabled: Boolean) {
        _isAutoVpnDisableEnabled.value = isEnabled
    }

    fun setAutoVpnDisableEnabled(context: Context, isEnabled: Boolean) {
        setAutoVpnDisableEnabled(isEnabled)
        updatePreference(context) { putBoolean(KEY_AUTO_VPN_DISABLE, isEnabled) }
    }

    fun isAutoVpnDisableEnabledNow(): Boolean = _isAutoVpnDisableEnabled.value

    fun setAutoLaunchGameEnabled(isEnabled: Boolean) {
        _isAutoLaunchGameEnabled.value = isEnabled
    }

    fun setAutoLaunchGameEnabled(context: Context, isEnabled: Boolean) {
        setAutoLaunchGameEnabled(isEnabled)
        updatePreference(context) { putBoolean(KEY_AUTO_LAUNCH_GAME, isEnabled) }
    }

    fun isAutoLaunchGameEnabledNow(): Boolean = _isAutoLaunchGameEnabled.value

    fun setIpFilterEnabled(isEnabled: Boolean) {
        _isIpFilterEnabled.value = isEnabled
    }

    fun setIpFilterEnabled(context: Context, isEnabled: Boolean) {
        setIpFilterEnabled(isEnabled)
        updatePreference(context) { putBoolean(KEY_IP_FILTER_ENABLED, isEnabled) }
    }

    fun setIpFilterText(text: String) {
        _ipFilterText.value = text
    }

    fun setIpFilterText(context: Context, text: String) {
        setIpFilterText(text)
        updatePreference(context) { putString(KEY_IP_FILTER_TEXT, text) }
    }

    fun setPackageText(text: String) {
        _packageText.value = text
    }

    fun setPackageText(context: Context, text: String) {
        setPackageText(text)
        updatePreference(context) { putString(KEY_PACKAGE_TEXT, text) }
    }

    fun setAutoLaunchPackage(packageName: String?) {
        _autoLaunchPackage.value = packageName?.trim()?.ifEmpty { null }
    }

    fun setAutoLaunchPackage(context: Context, packageName: String?) {
        val normalized = packageName?.trim()?.ifEmpty { null }
        setAutoLaunchPackage(normalized)
        updatePreference(context) { putString(KEY_AUTO_LAUNCH_PACKAGE, normalized) }
    }

    fun setPortText(text: String) {
        _portText.value = text.filter { it.isDigit() }.take(5)
    }

    fun setPortText(context: Context, text: String) {
        val normalized = text.filter { it.isDigit() }.take(5)
        setPortText(normalized)
        updatePreference(context) { putString(KEY_PORT_TEXT, normalized) }
    }

    fun setInstallResultNotificationsEnabled(isEnabled: Boolean) {
        _isInstallResultNotificationsEnabled.value = isEnabled
    }

    fun setInstallResultNotificationsEnabled(context: Context, isEnabled: Boolean) {
        setInstallResultNotificationsEnabled(isEnabled)
        updatePreference(context) { putBoolean(KEY_INSTALL_RESULT_NOTIFICATIONS_ENABLED, isEnabled) }
    }

    fun isInstallResultNotificationsEnabledNow(): Boolean = _isInstallResultNotificationsEnabled.value

    fun setLastClientVersion(version: String?) {
        _lastClientVersion.value = version?.trim()?.ifEmpty { null }
    }

    fun setLastClientVersion(context: Context, version: String?) {
        val normalized = version?.trim()?.ifEmpty { null }
        setLastClientVersion(normalized)
        updatePreference(context) { putString(KEY_LAST_CLIENT_VERSION, normalized) }
    }

    fun setShowReinstallWarningAfterDelete(isEnabled: Boolean) {
        _showReinstallWarningAfterDelete.value = isEnabled
    }

    fun setShowReinstallWarningAfterDelete(context: Context, isEnabled: Boolean) {
        setShowReinstallWarningAfterDelete(isEnabled)
        updatePreference(context) { putBoolean(KEY_SHOW_REINSTALL_WARNING_AFTER_DELETE, isEnabled) }
    }

    fun shouldShowReinstallWarningAfterDeleteNow(): Boolean = _showReinstallWarningAfterDelete.value

    fun setAutoPrepareFilesEnabled(context: Context, isEnabled: Boolean) {
        _isAutoPrepareFilesEnabled.value = isEnabled
        updatePreference(context) { putBoolean(KEY_AUTO_PREPARE_FILES_ENABLED, isEnabled) }
    }

    fun isAutoPrepareFilesEnabledNow(): Boolean = _isAutoPrepareFilesEnabled.value

    fun setAutoPrepareFilesDelaySeconds(context: Context, text: String) {
        val normalized = text.filter { it.isDigit() }
            .take(3)
            .toIntOrNull()
            ?.coerceIn(1, 120)
            ?: 3
        _autoPrepareFilesDelaySeconds.value = normalized.toString()
        updatePreference(context) { putInt(KEY_AUTO_PREPARE_FILES_DELAY_SECONDS, normalized) }
    }

    fun autoPrepareFilesDelayMillisNow(): Long {
        val seconds = _autoPrepareFilesDelaySeconds.value.toIntOrNull()?.coerceIn(1, 120) ?: 3
        return seconds * 1_000L
    }

    fun markDeleteCleanupPending() {
        _deleteCleanupPending.value = true
    }

    fun clearDeleteCleanupPending() {
        _deleteCleanupPending.value = false
    }

    fun isDeleteCleanupPendingNow(): Boolean = _deleteCleanupPending.value

    fun captureSettingsNow(): VpnCaptureSettings {
        val port = _portText.value.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: GAME_PORT
        return VpnCaptureSettings(
            filterByIp = _isIpFilterEnabled.value,
            ipFilterText = _ipFilterText.value,
            packageText = _packageText.value,
            port = port,
            autoLaunchPackage = _autoLaunchPackage.value
        )
    }

    fun cleanupWarmupReason(): CleanupReasonSpec = cleanupWarmupReason

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportLogsText(): String {
        return _logs.value.asReversed().joinToString(separator = "\n")
    }

    fun log(message: String) {
        val timestamp = timeFormat.get()?.format(Date()) ?: ""
        val line = "[$timestamp] $message"
        Log.d(TAG, line)
        synchronized(logLock) {
            _logs.value = listOf(line) + _logs.value.take(MAX_LOGS - 1)
        }
    }
}
