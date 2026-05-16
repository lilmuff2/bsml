package lilmuff1.bsml.logging

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
