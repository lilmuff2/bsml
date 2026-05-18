package lilmuff1.bsml.service

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import lilmuff1.bsml.state.LatestFingerprintStore
import lilmuff1.bsml.state.ModFilesRepository

object LoginFailedRewritePrewarmer {
    private val warmedKeys = ConcurrentHashMap.newKeySet<String>()

    fun prewarm(context: Context) {
        val filesDir = context.filesDir
        val fingerprintJson = readStoredFingerprintJson(filesDir) ?: return
        val clientHelloHash = LatestFingerprintStore.readStoredClientHelloHash(filesDir) ?: return
        val modStateKey = ModFilesRepository.getPreparedStateSignature(context)
        val cacheKey = "$clientHelloHash|$modStateKey|${fingerprintJson.hashCode()}"
        if (!warmedKeys.add(cacheKey)) return

        LoginFailedRewriter(
            filesDir = filesDir,
            listPatchedAssetPaths = { ModFilesRepository.listPreparedPaths(context) },
            getPatchedAssetSha = { path -> ModFilesRepository.getPreparedSha(context, path) },
            openPatchedAsset = { path -> ModFilesRepository.openPreparedFile(context, path) },
            getCurrentModStateKey = { modStateKey }
        ).prewarmFingerprintJson(fingerprintJson, clientHelloHash)
    }

    private fun readStoredFingerprintJson(filesDir: File): String? {
        val file = File(filesDir, "last_server_fingerprint.json")
        if (!file.isFile) return null
        return runCatching { file.readText().trim().ifEmpty { null } }.getOrNull()
    }
}
