package lilmuff1.bsml.modpatch

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import lilmuff1.bsml.state.LatestFingerprintRepository
import lilmuff1.bsml.state.OriginalAssetsRepository
import org.json.JSONArray
import org.json.JSONObject

class OriginalAssetProvider(
    private val context: Context,
    private val workingDir: File? = null
) {
    private val filesDir: File = context.filesDir
    private val fingerprintFiles: Map<String, String> by lazy { readFingerprintFiles() }

    fun resolveCsv(tableName: String): OriginalAsset {
        val path = resolveCsvPath(tableName)
        val bytes = readWorking(path)
            ?: OriginalAssetsRepository.openByGamePath(context, path)
            ?: OriginalAssetsRepository.openByFileName(context, path.substringAfterLast('/'))
            ?: readCached(path)
            ?: downloadOriginal(path)
            ?: error("original_not_found:$path")
        return OriginalAsset(path = path, bytes = bytes)
    }

    private fun readWorking(path: String): ByteArray? {
        val dir = workingDir ?: return null
        val file = File(dir, path)
        return if (file.isFile) file.readBytes() else null
    }

    private fun resolveCsvPath(tableName: String): String {
        val fileName = "$tableName.csv"
        val candidates = fingerprintFiles.keys
            .filter { it == fileName || it.endsWith("/$fileName") }
            .sortedWith(compareBy<String> {
                when {
                    it.startsWith("csv_logic/") -> 0
                    it.startsWith("csv_client/") -> 1
                    else -> 2
                }
            }.thenBy { it })
        return candidates.firstOrNull() ?: "csv_logic/$fileName"
    }

    private fun readCached(path: String): ByteArray? {
        val rootSha = LatestFingerprintRepository.readStoredClientHelloHash(filesDir) ?: return null
        val file = File(originalCacheDir(), "$rootSha/$path")
        return if (file.isFile) file.readBytes() else null
    }

    private fun downloadOriginal(path: String): ByteArray? {
        if (fingerprintFiles[path] == null) return null
        val rootSha = LatestFingerprintRepository.readStoredClientHelloHash(filesDir) ?: return null
        val origins = LatestFingerprintRepository.readStoredOrigins(filesDir)
        if (origins.isEmpty()) return null
        origins.forEach { origin ->
            val url = "${origin.trimEnd('/')}/$rootSha/$path"
            val bytes = runCatching { download(url) }.getOrNull()
            if (bytes != null) {
                val file = File(originalCacheDir(), "$rootSha/$path")
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                return bytes
            }
        }
        return null
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        return connection.inputStream.use { it.readBytes() }
    }

    private fun readFingerprintFiles(): Map<String, String> {
        val file = File(filesDir, LAST_SERVER_FINGERPRINT_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val files = root.optJSONArray("files") ?: JSONArray()
            buildMap {
                for (index in 0 until files.length()) {
                    val item = files.optJSONObject(index) ?: continue
                    val path = item.optString("file", "").replace("\\/", "/").trim()
                    val sha = item.optString("sha", "").trim()
                    if (path.isNotEmpty() && sha.isNotEmpty()) put(path, sha)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun originalCacheDir(): File = File(filesDir, "original_asset_cache")
}

data class OriginalAsset(
    val path: String,
    val bytes: ByteArray
)

private const val LAST_SERVER_FINGERPRINT_FILE = "last_server_fingerprint.json"
