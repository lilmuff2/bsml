package lilmuff1.bsml.state

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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
    private val cleanupWarmupReason = CleanupReasonSpec(7, "CLIENT_CONTENT_UPDATE")
    private val cleanupDeleteReason = CleanupReasonSpec(8, "CLIENT_UPDATE_AVAILABLE")

    private val logLock = Any()
    private val significantTrafficVersion = AtomicLong(0)
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

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

    fun isAutoVpnDisableEnabledNow(): Boolean = _isAutoVpnDisableEnabled.value

    fun setAutoLaunchGameEnabled(isEnabled: Boolean) {
        _isAutoLaunchGameEnabled.value = isEnabled
    }

    fun isAutoLaunchGameEnabledNow(): Boolean = _isAutoLaunchGameEnabled.value

    fun setIpFilterEnabled(isEnabled: Boolean) {
        _isIpFilterEnabled.value = isEnabled
    }

    fun setIpFilterText(text: String) {
        _ipFilterText.value = text
    }

    fun setPackageText(text: String) {
        _packageText.value = text
    }

    fun setAutoLaunchPackage(packageName: String?) {
        _autoLaunchPackage.value = packageName?.trim()?.ifEmpty { null }
    }

    fun setPortText(text: String) {
        _portText.value = text.filter { it.isDigit() }.take(5)
    }

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

    fun cleanupDeleteReason(): CleanupReasonSpec = cleanupDeleteReason

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun significantTrafficVersionNow(): Long = significantTrafficVersion.get()

    fun log(message: String) {
        if (isSignificantTraffic(message)) {
            significantTrafficVersion.incrementAndGet()
        }
        val timestamp = timeFormat.get()?.format(Date()) ?: ""
        val line = "[$timestamp] $message"
        Log.d(TAG, line)
        synchronized(logLock) {
            _logs.value = listOf(line) + _logs.value.take(MAX_LOGS - 1)
        }
    }

    private fun isSignificantTraffic(message: String): Boolean {
        return message.startsWith("SC CLIENT_HELLO") ||
            message.startsWith("SC LOGIN_FAILED") ||
            message.startsWith("ASSET GET")
    }
}
