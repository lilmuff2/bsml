package lilmuff1.bsml.state

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import lilmuff1.bsml.modpatch.NbAssetsArchiveReader
import org.json.JSONObject

data class ImportedModMetadata(
    val title: String? = null,
    val description: String? = null,
    val author: String? = null,
    val version: String? = null,
    val gameVersion: Int? = null
)

data class ImportedModFeature(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val enabledByDefault: Boolean = true,
    val priority: Int = 0,
    val root: String? = null,
    val patches: List<String> = emptyList(),
    val conflicts: Set<String> = emptySet()
)

data class ImportedModFeatureGroup(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val type: String = "DEFAULT",
    val features: List<String> = emptyList()
)

data class ImportedModFeatureSelection(
    val enabledFeatureIds: Set<String> = emptySet(),
    val disabledGroupIds: Set<String> = emptySet()
)

data class ImportedModFeatureConflict(
    val featureName: String,
    val conflictName: String
)

data class ImportedModState(
    val fileName: String? = null,
    val metadata: ImportedModMetadata? = null,
    val isEnabled: Boolean = true,
    val features: List<ImportedModFeature> = emptyList(),
    val featureGroups: List<ImportedModFeatureGroup> = emptyList(),
    val featureSelection: ImportedModFeatureSelection = ImportedModFeatureSelection(),
    val iconLastModified: Long = 0L,
    val error: String? = null
)

object ImportedModRepository {
    private const val IMPORTED_DIR = "imported_mods"
    private const val ACTIVE_MOD_FILE = "active_mod.nbassets"
    private const val ACTIVE_METADATA_FILE = "active_mod_metadata.json"
    private const val ACTIVE_ICON_FILE = "active_mod_icon.png"
    private const val ACTIVE_FEATURE_SELECTION_FILE = "active_mod_feature_selection.json"
    private const val ACTIVE_MOD_STATE_FILE = "active_mod_state.json"

    private val _state = MutableStateFlow(ImportedModState())
    val state = _state.asStateFlow()

    fun refreshState(context: Context) {
        val archive = activeArchiveFile(context)
        val icon = activeIconFile(context)
        if (archive.isFile && !isValidArchive(archive)) {
            clearStoredMod(context)
            _state.value = ImportedModState(error = "Invalid mod archive")
            return
        }
        if (archive.isFile && !icon.isFile) {
            extractIcon(archive, icon)
        }
        val metadata = readMetadata(context)
        val manifest = archive.takeIf { it.isFile }?.let { NbAssetsArchiveReader.readManifest(it) }
        val selection = readFeatureSelection(context, manifest?.features.orEmpty(), manifest?.featureGroups.orEmpty())
        _state.value = ImportedModState(
            fileName = archive.takeIf { it.isFile }?.name,
            metadata = metadata,
            isEnabled = readModEnabled(context),
            features = manifest?.features.orEmpty(),
            featureGroups = manifest?.featureGroups.orEmpty(),
            featureSelection = selection,
            iconLastModified = icon.takeIf { it.isFile }?.lastModified() ?: 0L,
            error = null
        )
    }

    fun hasActiveMod(context: Context): Boolean = activeArchiveFile(context).isFile

    fun activeArchiveFile(context: Context): File = File(importedDir(context), ACTIVE_MOD_FILE)

    fun activeIconFile(context: Context): File = File(importedDir(context), ACTIVE_ICON_FILE)

    fun clear(context: Context) {
        clearStoredMod(context)
        _state.value = ImportedModState()
        ModFilesRepository.clearPreparedFiles(context)
    }

    private fun clearStoredMod(context: Context) {
        activeArchiveFile(context).delete()
        metadataFile(context).delete()
        activeIconFile(context).delete()
        featureSelectionFile(context).delete()
        modStateFile(context).delete()
    }

    fun importMod(context: Context, uri: Uri): Boolean {
        return runCatching {
            val dir = importedDir(context)
            dir.mkdirs()
            val tempArchive = File(dir, "import_candidate.nbassets")
            val archive = activeArchiveFile(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempArchive.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("open_failed")

            if (!isValidArchive(tempArchive)) {
                tempArchive.delete()
                clearStoredMod(context)
                ModFilesRepository.clearPreparedFiles(context)
                error("invalid_nbassets_archive")
            }

            tempArchive.copyTo(archive, overwrite = true)
            tempArchive.delete()
            val metadata = NbAssetsArchiveReader.readMetadata(archive)
            val manifest = NbAssetsArchiveReader.readManifest(archive)
            writeMetadata(context, metadata)
            writeModEnabled(context, true)
            val selection = defaultSelection(manifest.features, manifest.featureGroups)
            writeFeatureSelection(context, selection)
            val icon = activeIconFile(context)
            extractIcon(archive, icon)
            _state.value = ImportedModState(
                fileName = displayName(context, uri) ?: archive.name,
                metadata = metadata,
                isEnabled = true,
                features = manifest.features,
                featureGroups = manifest.featureGroups,
                featureSelection = selection,
                iconLastModified = icon.takeIf { it.isFile }?.lastModified() ?: 0L,
                error = null
            )
            ModFilesRepository.clearPreparedFiles(context)
            true
        }.getOrElse { error ->
            clearStoredMod(context)
            ModFilesRepository.clearPreparedFiles(context)
            _state.value = ImportedModState(error = error.message ?: error::class.java.simpleName)
            false
        }
    }

    private fun importedDir(context: Context): File = File(context.filesDir, IMPORTED_DIR)

    private fun metadataFile(context: Context): File = File(importedDir(context), ACTIVE_METADATA_FILE)

    private fun featureSelectionFile(context: Context): File = File(importedDir(context), ACTIVE_FEATURE_SELECTION_FILE)

    private fun modStateFile(context: Context): File = File(importedDir(context), ACTIVE_MOD_STATE_FILE)

    private fun writeMetadata(context: Context, metadata: ImportedModMetadata) {
        val json = JSONObject()
            .put("title", metadata.title)
            .put("description", metadata.description)
            .put("author", metadata.author)
            .put("version", metadata.version)
            .put("gameVersion", metadata.gameVersion)
        metadataFile(context).writeText(json.toString())
    }

    private fun readMetadata(context: Context): ImportedModMetadata? {
        val file = metadataFile(context)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            ImportedModMetadata(
                title = json.optString("title", "").ifEmpty { null },
                description = json.optString("description", "").ifEmpty { null },
                author = json.optString("author", "").ifEmpty { null },
                version = json.optString("version", "").ifEmpty { null },
                gameVersion = json.optInt("gameVersion").takeIf { json.has("gameVersion") }
            )
        }.getOrNull()
    }

    fun setModEnabled(context: Context, isEnabled: Boolean) {
        writeModEnabled(context, isEnabled)
        _state.value = _state.value.copy(isEnabled = isEnabled)
        ModFilesRepository.clearPreparedFiles(context)
        ModFilesRepository.refreshPreparedStateFromImportedMod(context)
    }

    private fun writeModEnabled(context: Context, isEnabled: Boolean) {
        modStateFile(context).writeText(JSONObject().put("enabled", isEnabled).toString())
    }

    private fun readModEnabled(context: Context): Boolean {
        val file = modStateFile(context)
        if (!file.isFile) return true
        return runCatching {
            JSONObject(file.readText()).optBoolean("enabled", true)
        }.getOrDefault(true)
    }

    fun updateFeatureSelection(
        context: Context,
        selection: ImportedModFeatureSelection,
        preferredFeatureId: String? = null
    ): ImportedModFeatureConflict? {
        val current = _state.value
        val disabledGroupIds = reconcileDisabledGroups(
            requested = selection.enabledFeatureIds,
            currentSelection = current.featureSelection,
            groups = current.featureGroups,
            preferredFeatureId = preferredFeatureId
        )
        val conflict = findConflict(selection.enabledFeatureIds, current.features, preferredFeatureId)
        val sanitized = sanitizeSelection(
            selection.enabledFeatureIds,
            current.features,
            current.featureGroups,
            preferredFeatureId,
            disabledGroupIds
        )
        writeFeatureSelection(context, sanitized)
        _state.value = current.copy(featureSelection = sanitized)
        ModFilesRepository.clearPreparedFiles(context)
        ModFilesRepository.refreshPreparedStateFromImportedMod(context)
        return conflict
    }

    private fun writeFeatureSelection(context: Context, selection: ImportedModFeatureSelection) {
        val enabledArray = org.json.JSONArray().apply {
            selection.enabledFeatureIds.sorted().forEach(::put)
        }
        val disabledGroupsArray = org.json.JSONArray().apply {
            selection.disabledGroupIds.sorted().forEach(::put)
        }
        featureSelectionFile(context).writeText(
            JSONObject()
                .put("enabledFeatureIds", enabledArray)
                .put("disabledGroupIds", disabledGroupsArray)
                .toString()
        )
    }

    private fun readFeatureSelection(
        context: Context,
        features: List<ImportedModFeature>,
        groups: List<ImportedModFeatureGroup>
    ): ImportedModFeatureSelection {
        val defaults = defaultSelection(features, groups)
        val file = featureSelectionFile(context)
        if (!file.isFile) {
            if (features.isNotEmpty()) {
                writeFeatureSelection(context, defaults)
            }
            return defaults
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("enabledFeatureIds")
            val requested = buildSet {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            val disabledGroupsArray = json.optJSONArray("disabledGroupIds")
            val disabledGroupIds = buildSet {
                if (disabledGroupsArray != null) {
                    for (index in 0 until disabledGroupsArray.length()) {
                        disabledGroupsArray.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            sanitizeSelection(requested, features, groups, disabledGroupIds = disabledGroupIds)
        }.getOrElse {
            if (features.isNotEmpty()) {
                writeFeatureSelection(context, defaults)
            }
            defaults
        }
    }

    private fun defaultSelection(
        features: List<ImportedModFeature>,
        groups: List<ImportedModFeatureGroup>
    ): ImportedModFeatureSelection {
        val radioGroupFeatureIds = groups
            .filter { it.type == "RADIO_GROUP" }
            .flatMap { it.features }
            .toSet()
        val enabled = buildSet {
            features.filter { it.enabledByDefault && it.id !in radioGroupFeatureIds }
                .forEach { add(it.id) }
            groups.filter { it.type == "RADIO_GROUP" }.forEach { group ->
                group.features.firstOrNull { featureId ->
                    features.firstOrNull { it.id == featureId }?.enabledByDefault == true
                }?.let(::add)
                    ?: group.features.firstOrNull()?.let(::add)
            }
        }
        return sanitizeSelection(enabled, features, groups)
    }

    private fun reconcileDisabledGroups(
        requested: Set<String>,
        currentSelection: ImportedModFeatureSelection,
        groups: List<ImportedModFeatureGroup>,
        preferredFeatureId: String? = null
    ): Set<String> {
        val disabledGroups = currentSelection.disabledGroupIds.toMutableSet()
        groups.filter { it.type == "RADIO_GROUP" }.forEach { group ->
            val groupFeatureIds = group.features.toSet()
            val hasRequestedSelection = requested.any { it in groupFeatureIds }
            val hadCurrentSelection = currentSelection.enabledFeatureIds.any { it in groupFeatureIds }
            val preferredInGroup = preferredFeatureId != null && preferredFeatureId in groupFeatureIds
            when {
                hasRequestedSelection || preferredInGroup -> disabledGroups -= group.id
                hadCurrentSelection && !hasRequestedSelection && preferredFeatureId == null -> disabledGroups += group.id
            }
        }
        return disabledGroups
    }

    private fun sanitizeSelection(
        requested: Set<String>,
        features: List<ImportedModFeature>,
        groups: List<ImportedModFeatureGroup>,
        preferredFeatureId: String? = null,
        disabledGroupIds: Set<String> = emptySet()
    ): ImportedModFeatureSelection {
        val featureMap = features.associateBy { it.id }
        val validRequested = requested.filter { it in featureMap }.toMutableSet()

        groups.filter { it.type == "RADIO_GROUP" }.forEach { group ->
            val selected = group.features.filter { it in validRequested }
            if (selected.size > 1) {
                selected.drop(1).forEach(validRequested::remove)
            }
            if (selected.isEmpty() && group.id !in disabledGroupIds) {
                group.features.firstOrNull { featureId ->
                    featureMap[featureId]?.enabledByDefault == true
                }?.let(validRequested::add)
            }
        }

        val orderedFeatureIds = buildList {
            preferredFeatureId?.takeIf { it in validRequested }?.let(::add)
            validRequested.filter { it != preferredFeatureId }.forEach(::add)
        }
        val finalSet = LinkedHashSet<String>()
        orderedFeatureIds.forEach { featureId ->
            val conflicts = featureMap[featureId]?.conflicts.orEmpty()
            val conflictsExisting = finalSet.any { existingId ->
                existingId in conflicts || featureId in (featureMap[existingId]?.conflicts.orEmpty())
            }
            if (!conflictsExisting) {
                finalSet += featureId
            }
        }
        val validDisabledGroups = disabledGroupIds
            .filter { disabledGroupId -> groups.any { it.id == disabledGroupId && it.type == "RADIO_GROUP" } }
            .toSet()
        return ImportedModFeatureSelection(
            enabledFeatureIds = finalSet,
            disabledGroupIds = validDisabledGroups
        )
    }

    private fun findConflict(
        requested: Set<String>,
        features: List<ImportedModFeature>,
        preferredFeatureId: String?
    ): ImportedModFeatureConflict? {
        val featureMap = features.associateBy { it.id }
        fun displayName(id: String): String = featureMap[id]?.name?.takeIf { it.isNotBlank() } ?: id
        val validRequested = requested.filter { it in featureMap }
        val candidates = buildList {
            preferredFeatureId?.takeIf { it in validRequested }?.let(::add)
            validRequested.filter { it != preferredFeatureId }.forEach(::add)
        }
        candidates.forEach { featureId ->
            val conflicts = featureMap[featureId]?.conflicts.orEmpty()
            val conflictId = validRequested.firstOrNull { otherId ->
                otherId != featureId &&
                    (otherId in conflicts || featureId in featureMap[otherId]?.conflicts.orEmpty())
            }
            if (conflictId != null) {
                return ImportedModFeatureConflict(displayName(featureId), displayName(conflictId))
            }
        }
        return null
    }

    private fun isValidArchive(archive: File): Boolean {
        return runCatching {
            ZipFile(archive).use { zip ->
                zip.getEntry("content.json") ?: return@runCatching false
            }
            NbAssetsArchiveReader.readRootContentJson(archive)
            true
        }.getOrDefault(false)
    }

    private fun extractIcon(archive: File, output: File) {
        output.delete()
        runCatching {
            ZipFile(archive).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull {
                    it.name.replace('\\', '/').trimStart('/').equals("icon.png", ignoreCase = true)
                } ?: run {
                    VpnLogRepository.log("NBASSETS icon not found")
                    return
                }
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
                VpnLogRepository.log("NBASSETS icon saved entry=${entry.name} bytes=${output.length()}")
            }
        }.getOrElse { error ->
            VpnLogRepository.log("NBASSETS icon failed error=${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }
}
