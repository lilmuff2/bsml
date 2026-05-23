package lilmuff1.bsml.state

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.UUID
import java.util.jar.JarFile
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
    val gameVersion: Int? = null,
    val isSignatureVerified: Boolean = false
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
    val mods: List<ImportedModListItem> = emptyList(),
    val selectedModId: String? = null,
    val fileName: String? = null,
    val metadata: ImportedModMetadata? = null,
    val isEnabled: Boolean = true,
    val features: List<ImportedModFeature> = emptyList(),
    val featureGroups: List<ImportedModFeatureGroup> = emptyList(),
    val featureSelection: ImportedModFeatureSelection = ImportedModFeatureSelection(),
    val iconLastModified: Long = 0L,
    val error: String? = null
)

data class ImportedModListItem(
    val id: String,
    val fileName: String,
    val metadata: ImportedModMetadata? = null,
    val isEnabled: Boolean = true,
    val iconLastModified: Long = 0L,
    val features: List<ImportedModFeature> = emptyList(),
    val featureGroups: List<ImportedModFeatureGroup> = emptyList(),
    val featureSelection: ImportedModFeatureSelection = ImportedModFeatureSelection()
)

data class ImportModResult(
    val success: Boolean,
    val isSignatureVerified: Boolean = false
)

object ImportedModRepository {
    private const val IMPORTED_DIR = "imported_mods"
    private const val MOD_METADATA_FILE = "metadata.json"
    private const val MOD_ICON_FILE = "icon.png"
    private const val MOD_FEATURE_SELECTION_FILE = "feature_selection.json"
    private const val MOD_STATE_FILE = "mod_state.json"
    private const val MOD_ORDER_FILE = "mod_order.json"
    private val TRUSTED_SIGNER_SHA256 = setOf(
        "d21ca0f43cf708c0e86f25234411e31b491f8a0dacf504052e2b63f0bc93e3c9",
        "a997d4fa74c511f6550057fdb27832e823c5506f7e126b2127e4431f2da652cc",
        "8581f53fedaa7eed752b8944bfb2ccdadfb55fe1124c391176f157b882176e7f"
    )

    private val _state = MutableStateFlow(ImportedModState())
    val state = _state.asStateFlow()

    fun refreshState(context: Context) {
        val orderedIds = readModOrder(context)
        val allMods = listModDirs(context)
            .sortedWith(compareBy<File> { dir -> orderedIds.indexOf(dir.name).takeIf { it >= 0 } ?: Int.MAX_VALUE }.thenBy { it.name })
            .mapNotNull { dir ->
            if (!isValidArchive(dir)) {
                dir.deleteRecursively()
                return@mapNotNull null
            }
            val icon = iconFile(dir)
            if (!icon.isFile && !dir.isDirectory) {
                extractIcon(dir, icon)
            }
            val metadata = readMetadata(dir)
            val manifest = NbAssetsArchiveReader.readManifest(dir)
            val selection = readFeatureSelection(dir, manifest.features, manifest.featureGroups)
            ImportedModListItem(
                id = dir.name,
                fileName = dir.name,
                metadata = metadata,
                isEnabled = readModEnabled(dir),
                iconLastModified = icon.takeIf { it.isFile }?.lastModified() ?: 0L,
                features = manifest.features,
                featureGroups = manifest.featureGroups,
                featureSelection = selection
            )
        }
        writeModOrder(context, allMods.map { it.id })
        val selectedId = _state.value.selectedModId?.takeIf { id -> allMods.any { it.id == id } }
            ?: allMods.firstOrNull { it.isEnabled }?.id
            ?: allMods.firstOrNull()?.id
        val selected = allMods.firstOrNull { it.id == selectedId }
        _state.value = ImportedModState(
            mods = allMods,
            selectedModId = selectedId,
            fileName = selected?.fileName,
            metadata = selected?.metadata,
            isEnabled = selected?.isEnabled ?: true,
            features = selected?.features.orEmpty(),
            featureGroups = selected?.featureGroups.orEmpty(),
            featureSelection = selected?.featureSelection ?: ImportedModFeatureSelection(),
            iconLastModified = selected?.iconLastModified ?: 0L,
            error = null
        )
    }

    fun hasActiveMod(context: Context): Boolean = listModDirs(context).isNotEmpty()

    fun activeArchiveFile(context: Context): File {
        refreshState(context)
        val selectedId = _state.value.mods.firstOrNull { it.isEnabled }?.id
            ?: _state.value.mods.firstOrNull()?.id
            ?: return importedDir(context)
        return File(importedDir(context), selectedId)
    }

    fun activeIconFile(context: Context): File {
        refreshState(context)
        val selectedId = _state.value.mods.firstOrNull { it.isEnabled }?.id
            ?: _state.value.mods.firstOrNull()?.id
            ?: return File(importedDir(context), MOD_ICON_FILE)
        return iconFile(File(importedDir(context), selectedId))
    }

    fun iconFile(context: Context, modId: String): File = iconFile(File(importedDir(context), modId))

    fun enabledArchives(context: Context): List<File> {
        refreshState(context)
        return _state.value.mods
            .filter { it.isEnabled }
            .asReversed()
            .map { File(importedDir(context), it.id) }
    }

    fun moveModUp(context: Context, modId: String) {
        val order = readModOrder(context).toMutableList()
        val index = order.indexOf(modId)
        if (index <= 0) return
        java.util.Collections.swap(order, index, index - 1)
        writeModOrder(context, order)
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
    }

    fun moveModDown(context: Context, modId: String) {
        val order = readModOrder(context).toMutableList()
        val index = order.indexOf(modId)
        if (index < 0 || index >= order.lastIndex) return
        java.util.Collections.swap(order, index, index + 1)
        writeModOrder(context, order)
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
    }

    fun selectMod(context: Context, modId: String) {
        _state.value = _state.value.copy(selectedModId = modId)
    }

    fun clear(context: Context) {
        val selectedId = _state.value.selectedModId
            ?: _state.value.mods.firstOrNull { it.isEnabled }?.id
            ?: _state.value.mods.firstOrNull()?.id
            ?: return
        clearStoredModDir(File(importedDir(context), selectedId))
        _state.value = ImportedModState()
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
    }

    fun clear(context: Context, modId: String) {
        clearStoredModDir(File(importedDir(context), modId))
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
    }

    private fun clearStoredModDir(dir: File) {
        dir.deleteRecursively()
    }

    fun importMod(context: Context, uri: Uri): ImportModResult {
        return runCatching {
            val dir = importedDir(context)
            dir.mkdirs()
            val tempArchive = File(dir, "import_candidate.nbassets")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempArchive.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("open_failed")

            if (!isValidArchive(tempArchive)) {
                tempArchive.delete()
                ModFilesRepository.clearPreparedFiles(context)
                error("invalid_nbassets_archive")
            }

            val signatureVerified = verifyJarSignature(tempArchive)
            val modificationId = readModificationId(tempArchive) ?: UUID.randomUUID().toString()
            val modDir = File(dir, modificationId)
            if (modDir.exists()) {
                modDir.deleteRecursively()
            }
            modDir.mkdirs()
            unzipToDirectory(tempArchive, modDir)
            tempArchive.delete()
            val metadata = NbAssetsArchiveReader.readMetadata(modDir)
                .copy(isSignatureVerified = signatureVerified)
            val manifest = NbAssetsArchiveReader.readManifest(modDir)
            writeMetadata(modDir, metadata)
            writeModEnabled(modDir, true)
            val selection = defaultSelection(manifest.features, manifest.featureGroups)
            writeFeatureSelection(modDir, selection)
            val icon = iconFile(modDir)
            writeModOrder(context, readModOrder(context).filterNot { it == modDir.name } + modDir.name)
            ModFilesRepository.clearPreparedFiles(context)
            refreshState(context)
            _state.value = _state.value.copy(selectedModId = modDir.name)
            VpnLogRepository.log("NBASSETS import modId=${modDir.name} signatureVerified=$signatureVerified")
            ImportModResult(success = true, isSignatureVerified = signatureVerified)
        }.getOrElse { error ->
            ModFilesRepository.clearPreparedFiles(context)
            _state.value = _state.value.copy(error = error.message ?: error::class.java.simpleName)
            ImportModResult(success = false)
        }
    }

    private fun importedDir(context: Context): File = File(context.filesDir, IMPORTED_DIR)

    private fun modDirsRoot(context: Context): File = importedDir(context)

    private fun listModDirs(context: Context): List<File> {
        return modDirsRoot(context).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    }

    private fun iconFile(modDir: File): File = File(modDir, MOD_ICON_FILE)

    private fun metadataFile(modDir: File): File = File(modDir, MOD_METADATA_FILE)

    private fun featureSelectionFile(modDir: File): File = File(modDir, MOD_FEATURE_SELECTION_FILE)

    private fun modStateFile(modDir: File): File = File(modDir, MOD_STATE_FILE)

    private fun modOrderFile(context: Context): File = File(importedDir(context), MOD_ORDER_FILE)

    private fun writeMetadata(modDir: File, metadata: ImportedModMetadata) {
        val json = JSONObject()
            .put("title", metadata.title)
            .put("description", metadata.description)
            .put("author", metadata.author)
            .put("version", metadata.version)
            .put("gameVersion", metadata.gameVersion)
            .put("isSignatureVerified", metadata.isSignatureVerified)
        metadataFile(modDir).writeText(json.toString())
    }

    private fun readMetadata(modDir: File): ImportedModMetadata? {
        return runCatching {
            val live = NbAssetsArchiveReader.readMetadata(modDir)
            val cached = readCachedMetadata(modDir)
            live.copy(
                isSignatureVerified = cached?.isSignatureVerified ?: true
            )
        }.getOrElse {
            readCachedMetadata(modDir)
        }
    }

    private fun readCachedMetadata(modDir: File): ImportedModMetadata? {
        val file = metadataFile(modDir)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            ImportedModMetadata(
                title = json.optString("title", "").ifEmpty { null },
                description = json.optString("description", "").ifEmpty { null },
                author = json.optString("author", "").ifEmpty { null },
                version = json.optString("version", "").ifEmpty { null },
                gameVersion = json.optInt("gameVersion").takeIf { json.has("gameVersion") },
                isSignatureVerified = if (json.has("isSignatureVerified")) {
                    json.optBoolean("isSignatureVerified", false)
                } else {
                    true
                }
            )
        }.getOrNull()
    }

    fun setModEnabled(context: Context, isEnabled: Boolean) {
        val modDir = selectedModDir(context) ?: return
        writeModEnabled(modDir, isEnabled)
        _state.value = _state.value.copy(isEnabled = isEnabled)
        ModFilesRepository.clearPreparedFiles(context)
        ModFilesRepository.refreshPreparedStateFromImportedMod(context)
    }

    fun setModEnabled(context: Context, modId: String, isEnabled: Boolean) {
        val modDir = File(importedDir(context), modId)
        if (!modDir.isDirectory) return
        writeModEnabled(modDir, isEnabled)
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
    }

    private fun writeModEnabled(modDir: File, isEnabled: Boolean) {
        modStateFile(modDir).writeText(JSONObject().put("enabled", isEnabled).toString())
    }

    private fun readModEnabled(modDir: File): Boolean {
        val file = modStateFile(modDir)
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
        val modDir = selectedModDir(context) ?: return null
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
        writeFeatureSelection(modDir, sanitized)
        _state.value = current.copy(featureSelection = sanitized)
        ModFilesRepository.clearPreparedFiles(context)
        ModFilesRepository.refreshPreparedStateFromImportedMod(context)
        return conflict
    }

    fun updateFeatureSelection(
        context: Context,
        modId: String,
        selection: ImportedModFeatureSelection,
        preferredFeatureId: String? = null
    ): ImportedModFeatureConflict? {
        val current = refreshAndGetMod(context, modId) ?: return null
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
        writeFeatureSelection(File(importedDir(context), modId), sanitized)
        ModFilesRepository.clearPreparedFiles(context)
        refreshState(context)
        return conflict
    }

    private fun writeFeatureSelection(modDir: File, selection: ImportedModFeatureSelection) {
        val enabledArray = org.json.JSONArray().apply {
            selection.enabledFeatureIds.sorted().forEach(::put)
        }
        val disabledGroupsArray = org.json.JSONArray().apply {
            selection.disabledGroupIds.sorted().forEach(::put)
        }
        featureSelectionFile(modDir).writeText(
            JSONObject()
                .put("enabledFeatureIds", enabledArray)
                .put("disabledGroupIds", disabledGroupsArray)
                .toString()
        )
    }

    private fun readFeatureSelection(
        modDir: File,
        features: List<ImportedModFeature>,
        groups: List<ImportedModFeatureGroup>
    ): ImportedModFeatureSelection {
        val defaults = defaultSelection(features, groups)
        val file = featureSelectionFile(modDir)
        if (!file.isFile) {
            if (features.isNotEmpty()) {
                writeFeatureSelection(modDir, defaults)
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
                writeFeatureSelection(modDir, defaults)
            }
            defaults
        }
    }

    private fun selectedModDir(context: Context): File? {
        val id = _state.value.selectedModId
            ?: _state.value.mods.firstOrNull { it.isEnabled }?.id
            ?: _state.value.mods.firstOrNull()?.id
            ?: return null
        return File(importedDir(context), id).takeIf { it.isDirectory }
    }

    private fun refreshAndGetMod(context: Context, modId: String): ImportedModListItem? {
        refreshState(context)
        return _state.value.mods.firstOrNull { it.id == modId }
    }

    private fun readModOrder(context: Context): List<String> {
        val file = modOrderFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("order")
            buildList {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeModOrder(context: Context, order: List<String>) {
        val unique = order.distinct()
        val array = org.json.JSONArray().apply { unique.forEach(::put) }
        modOrderFile(context).parentFile?.mkdirs()
        modOrderFile(context).writeText(JSONObject().put("order", array).toString())
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
            if (archive.isDirectory) {
                File(archive, "content.json").isFile
            } else {
                ZipFile(archive).use { zip ->
                    zip.getEntry("content.json") ?: return@runCatching false
                }
            }
            NbAssetsArchiveReader.readRootContentJson(archive)
            true
        }.getOrDefault(false)
    }

    private fun readModificationId(archive: File): String? {
        return runCatching {
            JarFile(archive, false).use { jar ->
                jar.manifest?.mainAttributes
                    ?.getValue("Modification-Id")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::normalizeModificationId)
            }
        }.getOrNull()
    }

    private fun normalizeModificationId(value: String): String {
        return value.filter { char ->
            char.isLetterOrDigit() || char == '-' || char == '_'
        }.ifEmpty { UUID.randomUUID().toString() }
    }

    private fun verifyJarSignature(archive: File): Boolean {
        return runCatching {
            JarFile(archive, true).use { jar ->
                val buffer = ByteArray(32 * 1024)
                val signerCertificates = LinkedHashSet<Certificate>()
                val entries = jar.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { it.name.replace('\\', '/').startsWith("META-INF/") }
                    .toList()
                if (entries.isEmpty()) return@runCatching false
                entries.forEach { entry ->
                    jar.getInputStream(entry).use { input ->
                        while (input.read(buffer) != -1) {
                            // Reading the whole stream forces JarFile signature verification.
                        }
                    }
                    if (entry.codeSigners.isNullOrEmpty()) {
                        return@runCatching false
                    }
                    entry.codeSigners.orEmpty().forEach { signer ->
                        signer.signerCertPath.certificates.forEach(signerCertificates::add)
                    }
                }
                val signerFingerprints = signerCertificates.map(::certificateSha256).toSet()
                val trusted = signerFingerprints.any { it in TRUSTED_SIGNER_SHA256 }
                if (!trusted) {
                    VpnLogRepository.log("NBASSETS signature untrusted certs=${signerFingerprints.sorted()}")
                }
                trusted
            }
        }.getOrElse { error ->
            VpnLogRepository.log("NBASSETS signature verify failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            false
        }
    }

    private fun certificateSha256(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun extractIcon(source: File, output: File) {
        if (source.isDirectory && output == File(source, "icon.png")) {
            return
        }
        output.delete()
        runCatching {
            if (source.isDirectory) {
                val icon = File(source, "icon.png")
                if (!icon.isFile) {
                    VpnLogRepository.log("NBASSETS icon not found")
                    return
                }
                icon.copyTo(output, overwrite = true)
                VpnLogRepository.log("NBASSETS icon saved entry=icon.png bytes=${output.length()}")
            } else {
                ZipFile(source).use { zip ->
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
            }
        }.getOrElse { error ->
            VpnLogRepository.log("NBASSETS icon failed error=${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        }
    }

    private fun unzipToDirectory(archive: File, outputDir: File) {
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (normalized.startsWith("META-INF/") || normalized.startsWith("build/")) {
                    return@forEach
                }
                val output = File(outputDir, normalized)
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { input.copyTo(it) }
                    }
                }
            }
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
