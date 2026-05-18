package lilmuff1.bsml.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OriginalAssetsState(
    val folderName: String? = null
)

object OriginalAssetsRepository {
    private const val PREFS_NAME = "original_assets_repository"
    private const val KEY_TREE_URI = "tree_uri"

    private val _state = MutableStateFlow(OriginalAssetsState())
    val state = _state.asStateFlow()

    fun refreshState(context: Context) {
        _state.value = OriginalAssetsState(folderName = getDisplayName(context))
    }

    fun getTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(context: Context, uri: Uri?) {
        if (uri == null) {
            getTreeUri(context)?.let { previousUri ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previousUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
        }
        val editor = prefs(context).edit()
        if (uri == null) {
            editor.remove(KEY_TREE_URI)
        } else {
            editor.putString(KEY_TREE_URI, uri.toString())
        }
        editor.commit()
        refreshState(context)
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun openByGamePath(context: Context, path: String): ByteArray? {
        val root = getRoot(context) ?: return null
        val normalized = normalizePath(path)
        return findByPath(root, normalized)?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }
    }

    fun openByFileName(context: Context, fileName: String): ByteArray? {
        val root = getRoot(context) ?: return null
        return findByFileName(root, fileName)?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }
    }

    private fun getDisplayName(context: Context): String? {
        val root = getRoot(context) ?: return null
        return root.name ?: getTreeUri(context)?.lastPathSegment
    }

    private fun getRoot(context: Context): DocumentFile? {
        val uri = getTreeUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
    }

    private fun findByPath(directory: DocumentFile, relativePath: String): DocumentFile? {
        var current = directory
        val parts = relativePath.split('/').filter(String::isNotEmpty)
        for (part in parts) {
            current = current.findFile(part) ?: return null
        }
        return current.takeIf { it.isFile }
    }

    private fun findByFileName(directory: DocumentFile, fileName: String): DocumentFile? {
        directory.listFiles().forEach { child ->
            when {
                child.isFile && child.name == fileName -> return child
                child.isDirectory -> findByFileName(child, fileName)?.let { return it }
            }
        }
        return null
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').trimStart('/')
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
