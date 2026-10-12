package app.sonorium.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.sonorium.net.SonoriumApi
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A theme folder on this phone, in the server's format (audio, group folders, metadata.json, presets.json). */
data class LocalTheme(
    /** The folder's document id (stable while the folder exists). */
    val docId: String,
    val folder: String,
    val name: String,
    val icon: String = "",
    val description: String = "",
    val categories: List<String> = emptyList(),
    val tracks: Int = 0,
    val bytes: Long = 0,
)

/**
 * The themes kept on this phone, in a folder the user picked once (Storage Access Framework: works on
 * Android 8+ without broad storage permissions, and the folder stays reachable in the Files app, so a
 * theme folder copied in by hand shows up too).
 */
class ThemeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    /** The picked folder (a tree URI), or null before the first Import / Download. */
    val root: Uri? get() = prefs.getString(KEY_ROOT, null)?.let(Uri::parse)?.takeIf { hasAccess(it) }

    private fun hasAccess(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /** Keeps the folder the user picked (from ACTION_OPEN_DOCUMENT_TREE). */
    fun setRoot(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
    }

    private data class Doc(val id: String, val name: String, val mime: String, val size: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(tree: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3))) }
        }.orEmpty()
    }

    private fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private fun readText(tree: Uri, docId: String): String? = runCatching {
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    /** Every theme folder (a folder with a metadata.json or audio files). */
    suspend fun list(): List<LocalTheme> = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext emptyList()
        children(tree, rootId(tree)).filter { it.isDir }.mapNotNull { dir -> describe(tree, dir) }.sortedBy { it.name.lowercase() }
    }

    private fun describe(tree: Uri, dir: Doc): LocalTheme? {
        val files = children(tree, dir.id)
        val audio = files.filter { !it.isDir && isAudio(it.name) }.toMutableList()
        var bytes = files.filter { !it.isDir }.sumOf { it.size }
        files.filter { it.isDir }.forEach { g ->
            val inner = children(tree, g.id)
            audio += inner.filter { !it.isDir && isAudio(it.name) }
            bytes += inner.filter { !it.isDir }.sumOf { it.size }
        }
        val meta = files.firstOrNull { it.name == "metadata.json" }?.let { readText(tree, it.id) }
            ?.let { runCatching { SonoriumApi.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (meta == null && audio.isEmpty()) return null
        fun s(k: String) = (meta?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return LocalTheme(
            docId = dir.id, folder = dir.name, name = s("name")?.ifBlank { null } ?: dir.name,
            icon = s("icon").orEmpty(), description = s("description").orEmpty(),
            categories = (meta?.get("categories") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            tracks = audio.size, bytes = bytes,
        )
    }

    /**
     * Imports a theme zip as the server's Export makes it (one `<folder>/` with the files). Returns the new
     * theme's folder name, or throws [ImportProblem] with a text for the user.
     */
    suspend fun importZip(input: InputStream, fallbackName: String): String = withContext(Dispatchers.IO) {
        val tree = root ?: throw ImportProblem("Choose a folder for themes first")
        val rootId = rootId(tree)
        val existing = children(tree, rootId).map { it.name.lowercase() }.toSet()
        var folderDoc: String? = null
        var folderName = ""
        val dirs = mutableMapOf<String, String>() // relative dir path → doc id
        var files = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val path = e.name.replace('\\', '/').trimStart('/')
                if (path.isEmpty() || path.split('/').any { it == ".." } || path.startsWith("__MACOSX")) continue
                val parts = path.trimEnd('/').split('/')
                // The zip's own top folder becomes the theme folder; files at the top go into one named after the zip
                val inFolder = parts.size > 1
                if (folderDoc == null) {
                    folderName = if (inFolder) parts[0] else fallbackName.substringBeforeLast('.')
                    if (folderName.lowercase() in existing) throw ImportProblem("“$folderName” is already on this phone")
                    folderDoc = create(tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, folderName)
                    dirs[""] = folderDoc!!
                }
                val rel = if (inFolder) parts.drop(1) else parts
                if (rel.isEmpty()) continue
                if (e.isDirectory) {
                    dirOf(tree, dirs, rel)
                    continue
                }
                val parent = dirOf(tree, dirs, rel.dropLast(1))
                val doc = create(tree, parent, mimeOf(rel.last()), rel.last())
                resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc))?.use { zip.copyTo(it) }
                files++
            }
        }
        if (folderDoc == null || files == 0) throw ImportProblem("This zip has no theme in it")
        folderName
    }

    private fun dirOf(tree: Uri, dirs: MutableMap<String, String>, rel: List<String>): String {
        var key = ""
        var id = dirs.getValue("")
        rel.forEach { part ->
            key = if (key.isEmpty()) part else "$key/$part"
            id = dirs.getOrPut(key) { create(tree, id, DocumentsContract.Document.MIME_TYPE_DIR, part) }
        }
        return id
    }

    private fun create(tree: Uri, parentId: String, mime: String, name: String): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw ImportProblem("Couldn't write to the themes folder")
        return DocumentsContract.getDocumentId(uri)
    }

    /** Removes a theme folder from the phone. */
    suspend fun remove(theme: LocalTheme): Boolean = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext false
        runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, theme.docId)) }.getOrDefault(false)
    }

    class ImportProblem(message: String) : Exception(message)

    companion object {
        private const val KEY_ROOT = "themeFolder"
        private val AUDIO = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")
        fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

        /** The folder picker's suggested start (Music/Sonorium when it exists; else Music). */
        fun suggestedStart(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
    }
}
