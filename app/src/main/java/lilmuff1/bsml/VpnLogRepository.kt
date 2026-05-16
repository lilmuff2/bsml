package lilmuff1.bsml

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnLogRepository {
    private const val TAG = "BSMLLocalVpn"
    private const val MAX_LOGS = 200

    private val logLock = Any()
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

    private val _isContentHashRewriteEnabled = MutableStateFlow(true)
    val isContentHashRewriteEnabled = _isContentHashRewriteEnabled.asStateFlow()

    private val _isFileShaRewriteEnabled = MutableStateFlow(true)
    val isFileShaRewriteEnabled = _isFileShaRewriteEnabled.asStateFlow()

    private val _isFingerprintRewriteEnabled = MutableStateFlow(true)
    val isFingerprintRewriteEnabled = _isFingerprintRewriteEnabled.asStateFlow()

    private val _isAutoVpnDisableEnabled = MutableStateFlow(false)
    val isAutoVpnDisableEnabled = _isAutoVpnDisableEnabled.asStateFlow()

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

    fun setContentHashRewriteEnabled(isEnabled: Boolean) {
        _isContentHashRewriteEnabled.value = isEnabled
    }

    fun isContentHashRewriteEnabledNow(): Boolean = _isContentHashRewriteEnabled.value

    fun setFileShaRewriteEnabled(isEnabled: Boolean) {
        _isFileShaRewriteEnabled.value = isEnabled
    }

    fun isFileShaRewriteEnabledNow(): Boolean = _isFileShaRewriteEnabled.value

    fun setFingerprintRewriteEnabled(isEnabled: Boolean) {
        _isFingerprintRewriteEnabled.value = isEnabled
    }

    fun isFingerprintRewriteEnabledNow(): Boolean = _isFingerprintRewriteEnabled.value

    fun setAutoVpnDisableEnabled(isEnabled: Boolean) {
        _isAutoVpnDisableEnabled.value = isEnabled
    }

    fun isAutoVpnDisableEnabledNow(): Boolean = _isAutoVpnDisableEnabled.value

    fun clearLogs() {
        _logs.value = emptyList()
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
