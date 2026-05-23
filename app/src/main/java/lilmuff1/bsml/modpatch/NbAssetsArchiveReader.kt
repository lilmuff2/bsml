package lilmuff1.bsml.modpatch

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import lilmuff1.bsml.state.ImportedModFeature
import lilmuff1.bsml.state.ImportedModFeatureGroup
import lilmuff1.bsml.state.ImportedModMetadata
import lilmuff1.bsml.state.VpnLogRepository
import org.json.JSONObject

object NbAssetsArchiveReader {
    data class Manifest(
        val features: List<ImportedModFeature> = emptyList(),
        val featureGroups: List<ImportedModFeatureGroup> = emptyList()
    )

    fun readMetadata(source: File): ImportedModMetadata {
        val json = runCatching { readRootContentJson(source) }.getOrNull() ?: return ImportedModMetadata()
        return ImportedModMetadata(
            title = localizedText(json.opt("@title")),
            description = localizedText(json.opt("@description")),
            author = json.optString("@author", "").ifEmpty { null },
            version = json.optString("@version", "").ifEmpty { null },
            gameVersion = json.optInt("@gv").takeIf { json.has("@gv") }
        )
    }

    fun readRootContentJson(source: File): JSONObject {
        if (source.isDirectory) {
            val file = File(source, ROOT_CONTENT_JSON)
            if (!file.isFile) error("content.json not found")
            return JSONObject(file.readText())
        }
        ZipFile(source).use { zip ->
            val entry = zip.getEntry(ROOT_CONTENT_JSON) ?: error("content.json not found")
            return JSONObject(zip.getInputStream(entry).use { it.readBytes().decodeToString() })
        }
    }

    fun readManifest(source: File): Manifest {
        val root = readRootContentJson(source)
        val features = root.optJSONObject("@features")?.let { featuresJson ->
            featuresJson.keys().asSequence()
                .mapNotNull { key ->
                    val json = featuresJson.optJSONObject(key) ?: return@mapNotNull null
                    ImportedModFeature(
                        id = key,
                        name = localizedText(json.opt("@name")),
                        description = localizedText(json.opt("@description")),
                        enabledByDefault = json.optBoolean("@enabled", true),
                        priority = json.optInt("@priority", 0),
                        root = json.optString("@root", key).ifEmpty { key },
                        patches = patchPaths(json),
                        conflicts = stringArray(json.opt("@conflicts")).toSet()
                    )
                }
                .sortedWith(compareBy<ImportedModFeature>({ it.priority }, { it.id }))
                .toList()
        }.orEmpty()
        val featureGroups = root.optJSONObject("@feature_groups")?.let { groupsJson ->
            groupsJson.keys().asSequence()
                .mapNotNull { key ->
                    val json = groupsJson.optJSONObject(key) ?: return@mapNotNull null
                    ImportedModFeatureGroup(
                        id = key,
                        name = localizedText(json.opt("@name")),
                        description = localizedText(json.opt("@description")),
                        type = json.optString("@type", "DEFAULT").ifEmpty { "DEFAULT" },
                        features = stringArray(json.opt("@features"))
                    )
                }
                .toList()
        }.orEmpty()
        return Manifest(
            features = features,
            featureGroups = featureGroups
        )
    }

    private fun localizedText(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val appLanguage = VpnLogRepository.appLanguageNow()
                val preferredTag = if (appLanguage == "system") {
                    Locale.getDefault().language
                } else {
                    appLanguage
                }
                val preferred = preferredTag.uppercase(Locale.ROOT)
                value.optString(preferred, "")
                    .ifEmpty { value.optString("EN", "") }
                    .ifEmpty { value.optString("RU", "") }
                    .ifEmpty { value.keys().asSequence().mapNotNull { key -> value.optString(key, "").takeIf { it.isNotEmpty() } }.firstOrNull() }
            }
            is String -> value.ifEmpty { null }
            else -> null
        }
    }

    private fun patchPaths(value: JSONObject): List<String> {
        return when (val patches = value.opt("@patches")) {
            is String -> listOfNotNull(patches.takeIf { it.isNotBlank() })
            is org.json.JSONArray -> buildList {
                for (index in 0 until patches.length()) {
                    patches.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

    private fun stringArray(value: Any?): List<String> {
        return when (value) {
            is String -> listOfNotNull(value.takeIf { it.isNotBlank() })
            is org.json.JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    value.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

}

const val ROOT_CONTENT_JSON = "content.json"

fun isNbAssetsMetadataKey(key: String): Boolean {
    return key.startsWith("@")
}
