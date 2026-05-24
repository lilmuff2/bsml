package lilmuff1.bsml.state

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String?,
    val apkUrl: String,
    val required: Boolean,
    val changelog: String?
)

data class UpdateState(
    val isChecking: Boolean = false,
    val update: UpdateInfo? = null,
    val error: String? = null
)

object UpdateRepository {
    private const val UPDATE_MANIFEST_URL = "http://lilmuff1.xyz/bsml/update.json"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()

    suspend fun checkForUpdates(context: Context) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(isChecking = true, error = null)
        runCatching {
            val manifest = fetchManifest()
            val currentVersionCode = currentVersionCode(context)
            val update = if (manifest.versionCode > currentVersionCode) manifest else null
            _state.value = UpdateState(isChecking = false, update = update, error = null)
            if (update != null) {
                VpnLogRepository.log(
                    "UPDATE available versionCode=${update.versionCode} current=$currentVersionCode required=${update.required}"
                )
            }
        }.getOrElse { error ->
            _state.value = UpdateState(isChecking = false, update = null, error = error.message ?: error::class.java.simpleName)
            VpnLogRepository.log("UPDATE check failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        }
    }

    private fun fetchManifest(): UpdateInfo {
        val connection = (URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}")
            }
            parseManifest(JSONObject(connection.inputStream.use { input -> input.readBytes().decodeToString() }))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseManifest(json: JSONObject): UpdateInfo {
        val versionCode = json.optLong("versionCode", -1L)
        val apkUrl = json.optString("apkUrl", "").trim()
        if (versionCode <= 0) error("versionCode missing")
        if (apkUrl.isBlank()) error("apkUrl missing")
        return UpdateInfo(
            versionCode = versionCode,
            versionName = json.optString("versionName", "").trim().ifEmpty { null },
            apkUrl = apkUrl,
            required = json.optBoolean("required", false),
            changelog = localizedChangelog(json.optJSONObject("changelog"))
        )
    }

    private fun localizedChangelog(changelog: JSONObject?): String? {
        if (changelog == null) return null
        val language = VpnLogRepository.appLanguageNow()
        val key = if (language == "system") {
            java.util.Locale.getDefault().language
        } else {
            language
        }
        return changelog.optString(key, "")
            .ifEmpty { changelog.optString("en", "") }
            .ifEmpty { changelog.optString("ru", "") }
            .ifEmpty {
                changelog.keys().asSequence()
                    .mapNotNull { changelog.optString(it, "").takeIf(String::isNotBlank) }
                    .firstOrNull()
                    .orEmpty()
            }
            .ifEmpty { null }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }
}
