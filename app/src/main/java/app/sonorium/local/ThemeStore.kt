package app.sonorium.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import app.sonorium.net.SonoriumApi
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    /** Downloaded from a server ([ThemeStore.MARKER]): the server's theme id and the package's content hash. */
    val themeId: String? = null,
    val contentHash: String? = null,
    /** The theme's own id from its metadata.json (the server's theme id, for a theme exported from a server). */
    val metaId: String? = null,
    /** One of the starter themes the app ships with, as copied in (not saved from a server). */
    val starter: Boolean = false,
) {
    /** Changes when the theme's files change (an update, or files copied in): its prepared copy is then stale. */
    val version: String get() = "$docId|${contentHash.orEmpty()}|$bytes|$tracks"
}

/**
 * The themes kept on this phone, in a folder the user picked once (Storage Access Framework: works on
 * Android 8+ without broad storage permissions, and the folder stays reachable in the Files app, so a
 * theme folder copied in by hand shows up too).
 */
class ThemeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    /** No folder picked yet; picked and reachable; or picked but gone (moved, deleted, or access revoked). */
    enum class FolderState { NONE, OK, MISSING }

    private val saved: Uri? get() = prefs.getString(KEY_ROOT, null)?.let(Uri::parse)

    /** The picked folder (a tree URI) while this app may still use it, or null. */
    val root: Uri? get() = saved?.takeIf { hasAccess(it) }

    private fun hasAccess(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /** Whether the themes folder is there (reads it: call off the main thread). */
    suspend fun folderState(): FolderState = withContext(Dispatchers.IO) {
        if (saved == null) return@withContext FolderState.NONE
        val tree = root ?: return@withContext FolderState.MISSING
        val found = runCatching {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree)),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR } == true
        }.onFailure { Log.w(TAG, "Themes folder check failed", it) }.getOrDefault(false)
        if (found) FolderState.OK else FolderState.MISSING
    }

    /**
     * Keeps the folder the user picked (from ACTION_OPEN_DOCUMENT_TREE) and lets go of the one before.
     * Returns true when it's a different folder from before.
     */
    fun setRoot(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        val old = saved
        prefs.edit { putString(KEY_ROOT, uri.toString()) }
        if (old != null && old != uri) {
            runCatching { resolver.releasePersistableUriPermission(old, flags) }.onFailure { Log.w(TAG, "Couldn't release the old folder", it) }
        }
        return old != uri
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

    /** Every theme folder (a folder with a metadata.json or audio files). Throws when the folder can't be read. */
    suspend fun list(): List<LocalTheme> = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext emptyList()
        val top = children(tree, rootId(tree))
        cleanLeftovers(tree, top)
        top.filter { it.isDir && !it.name.startsWith(".") }.mapNotNull { dir -> describe(tree, dir) }.sortedBy { it.name.lowercase() }
    }

    /** Work folders an import or download left behind (the app stopped half way), except ones in use now. */
    private fun cleanLeftovers(tree: Uri, top: List<Doc>) {
        top.filter { it.isDir && it.name.startsWith(WORK_PREFIX) && it.name !in busy }.forEach { d ->
            Log.i(TAG, "Removing leftover ${d.name}")
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, d.id)) }
        }
    }

    private fun describe(tree: Uri, dir: Doc): LocalTheme? {
        val files = children(tree, dir.id)
        val audio = files.filter { !it.isDir && isAudio(it.name) }.toMutableList()
        var bytes = files.filter { !it.isDir }.sumOf { it.size }
        files.filter { it.isDir && !it.name.startsWith(".") }.forEach { g ->
            val inner = children(tree, g.id)
            audio += inner.filter { !it.isDir && isAudio(it.name) }
            bytes += inner.filter { !it.isDir }.sumOf { it.size }
        }
        val meta = files.firstOrNull { it.name == "metadata.json" }?.let { readText(tree, it.id) }
            ?.let { runCatching { SonoriumApi.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (meta == null && audio.isEmpty()) return null
        fun s(k: String) = (meta?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val marker = files.firstOrNull { it.name == MARKER }?.let { readText(tree, it.id) }
            ?.let { runCatching { SonoriumApi.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        fun m(k: String) = (marker?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return LocalTheme(
            docId = dir.id, folder = dir.name, name = s("name")?.ifBlank { null } ?: dir.name,
            icon = s("icon").orEmpty(), description = s("description").orEmpty(),
            categories = (meta?.get("categories") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            tracks = audio.size, bytes = bytes, themeId = m("theme_id"), contentHash = m("content_hash"),
            metaId = s("id")?.ifBlank { null },
            starter = marker == null && s("id")?.let { it in StarterThemes.ids(context) } == true,
        )
    }

    /**
     * Imports a theme zip as the server's Export makes it (one `<folder>/` with the files): checked first,
     * written into a work folder, renamed once complete, and removed on any failure, so a failed import
     * never leaves a half theme behind. Throws [ImportProblem] with a text for the user.
     */
    suspend fun importZip(input: InputStream, zipName: String): ImportRules.Plan = withContext(Dispatchers.IO) {
        val tree = root ?: throw ImportProblem("Choose a folder for themes first")
        val rootId = rootId(tree)
        val copy = File.createTempFile("import", ".zip", context.cacheDir)
        try {
            copy.outputStream().use { out -> input.copyTo(out) }
            val zip = try {
                ZipFile(copy)
            } catch (e: ZipException) {
                Log.i(TAG, "Not a zip: $zipName", e)
                throw ImportProblem(ImportRules.NOT_A_ZIP)
            }
            zip.use {
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val existing = children(tree, rootId).map { it.name.lowercase() }.toSet()
                val plan = when (val r = ImportRules.plan(entries.map { it.name }, zipName, existing)) {
                    is ImportRules.Result.Problem -> throw ImportProblem(r.message)
                    is ImportRules.Result.Ok -> r.plan
                }
                val needed = plan.files.sumOf { (name, _) -> zip.getEntry(name)?.size?.coerceAtLeast(0) ?: 0 }
                if (!SpaceRules.enough(needed, SpaceRules.available(context.filesDir))) {
                    throw ImportProblem(SpaceRules.notEnoughText("to import this theme", needed))
                }
                val work = WORK_PREFIX + "import-" + System.currentTimeMillis()
                busy += work
                try {
                    val folder = create(tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, work)
                    try {
                        val dirs = mutableMapOf("" to folder)
                        plan.files.forEach { (name, rel) ->
                            coroutineContext.ensureActive()
                            val parts = rel.split('/')
                            val doc = create(tree, dirOf(tree, dirs, parts.dropLast(1)), mimeOf(parts.last()), parts.last())
                            val out = resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc))
                                ?: throw ImportProblem(CANT_WRITE)
                            out.use { o -> zip.getInputStream(zip.getEntry(name)).use { it.copyTo(o) } }
                        }
                        rename(tree, folder, plan.folderName)
                    } catch (e: Throwable) {
                        delete(tree, folder)
                        throw e
                    }
                } finally {
                    busy -= work
                }
                plan
            }
        } finally {
            copy.delete()
        }
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
        val uri = runCatching { DocumentsContract.createDocument(resolver, parent, mime, name) }
            .onFailure { Log.w(TAG, "Couldn't create $name", it) }.getOrNull() ?: throw ImportProblem(CANT_WRITE)
        return DocumentsContract.getDocumentId(uri)
    }

    /** Renames a folder at the top of the themes folder; returns its new document id. */
    private fun rename(tree: Uri, docId: String, name: String): String {
        val uri = runCatching { DocumentsContract.renameDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId), name) }
            .onFailure { Log.w(TAG, "Couldn't rename to $name", it) }.getOrNull() ?: throw ImportProblem(CANT_WRITE)
        return DocumentsContract.getDocumentId(uri)
    }

    private fun delete(tree: Uri, docId: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId)) }
            .onFailure { Log.w(TAG, "Couldn't delete $docId", it) }.getOrDefault(false)

    /** A file of a theme folder: its path inside the folder ("Birds/robin.mp3"), where to read it, its size. */
    data class ThemeFile(val path: String, val uri: Uri, val bytes: Long, val modified: Long)

    /** Every file of [theme] (the folder itself and its group folders). */
    suspend fun files(theme: LocalTheme): List<ThemeFile> = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext emptyList()
        fun walk(parentId: String, prefix: String, depth: Int): List<ThemeFile> = childrenFull(tree, parentId).flatMap { d ->
            when {
                d.isDir && depth < 2 && !d.name.startsWith(".") -> walk(d.id, prefix + d.name + "/", depth + 1)
                d.isDir -> emptyList()
                else -> listOf(ThemeFile(prefix + d.name, DocumentsContract.buildDocumentUriUsingTree(tree, d.id), d.size, d.modified))
            }
        }
        walk(theme.docId, "", 0)
    }

    private data class FullDoc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun childrenFull(tree: Uri, parentId: String): List<FullDoc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(FullDoc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
            }
        }.orEmpty()
    }

    /** A text file at the top of [theme]'s folder (presets.json, metadata.json), or null. */
    suspend fun readFile(theme: LocalTheme, name: String): String? = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext null
        runCatching { children(tree, theme.docId) }.getOrDefault(emptyList()).firstOrNull { it.name == name && !it.isDir }?.let { readText(tree, it.id) }
    }

    /**
     * Writes a file at the top of [theme]'s folder (metadata.json, presets.json), replacing it or making
     * it. Throws [ImportProblem] when the folder can't be written.
     */
    suspend fun writeFile(theme: LocalTheme, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val tree = root ?: throw ImportProblem(CANT_WRITE)
        val existing = runCatching { children(tree, theme.docId) }.getOrDefault(emptyList()).firstOrNull { it.name == name && !it.isDir }
        // "wt" replaces the content; a provider without it gets a new file instead (plain "w" may leave a longer old tail)
        val out = existing?.let { e -> runCatching { resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id), "wt") }.getOrNull() }
            ?: run {
                existing?.let { delete(tree, it.id) }
                resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, create(tree, theme.docId, mimeOf(name), name)), "w")
            }
            ?: throw ImportProblem(CANT_WRITE)
        out.use { it.write(bytes) }
    }

    /**
     * Repeats a Theme Editor change's file operations (Core's `theme_edit.FileOps`: mkdir, move, copy,
     * write, delete; paths relative to the theme folder with "/") in [theme]'s folder. "write" and "copy"
     * take the file from [from] (the prepared copy Core changed). Throws [ImportProblem] when one fails.
     */
    suspend fun applyOps(theme: LocalTheme, ops: List<FileOp>, from: File) = withContext(Dispatchers.IO) {
        val tree = root ?: throw ImportProblem(CANT_WRITE)
        fun find(path: String): Doc? {
            var parent = theme.docId
            var found: Doc? = null
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                found = children(tree, parent).firstOrNull { it.name == part } ?: return null
                parent = found.id
            }
            return found
        }
        fun dir(path: String): String {
            var parent = theme.docId
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                parent = children(tree, parent).firstOrNull { it.name == part && it.isDir }?.id
                    ?: create(tree, parent, DocumentsContract.Document.MIME_TYPE_DIR, part)
            }
            return parent
        }
        fun parentOf(path: String) = path.substringBeforeLast('/', "")
        fun nameOf(path: String) = path.substringAfterLast('/')
        for (op in ops) {
            when (op.op) {
                "mkdir" -> dir(op.dst ?: continue)
                "move" -> {
                    val src = op.src ?: continue
                    val dst = op.dst ?: continue
                    val doc = find(src) ?: throw ImportProblem("Couldn't find $src in the theme's folder")
                    var id = doc.id
                    if (parentOf(src) != parentOf(dst)) {
                        val fromParent = DocumentsContract.buildDocumentUriUsingTree(tree, find(parentOf(src))?.id ?: theme.docId)
                        val toParent = DocumentsContract.buildDocumentUriUsingTree(tree, dir(parentOf(dst)))
                        val moved = runCatching { DocumentsContract.moveDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), fromParent, toParent) }
                            .onFailure { Log.w(TAG, "Couldn't move $src", it) }.getOrNull()
                        id = if (moved != null) DocumentsContract.getDocumentId(moved) else copyFile(tree, doc, DocumentsContract.getDocumentId(toParent))
                    }
                    val name = nameOf(dst)
                    if (nameOf(src) != name) {
                        // A case-only rename goes through a temporary name (some storage ignores case)
                        if (name.equals(nameOf(src), ignoreCase = true)) id = rename(tree, id, "${WORK_PREFIX}rename-${System.currentTimeMillis()}")
                        rename(tree, id, name)
                    }
                }
                "write", "copy" -> {
                    val dst = op.dst ?: continue
                    val file = File(from, dst)
                    if (!file.isFile) throw ImportProblem("Couldn't find $dst to save")
                    val parent = dir(parentOf(dst))
                    val name = nameOf(dst)
                    val existing = children(tree, parent).firstOrNull { it.name == name && !it.isDir }
                    val out = existing?.let { e -> runCatching { resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id), "wt") }.getOrNull() }
                        ?: run {
                            existing?.let { delete(tree, it.id) }
                            resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, create(tree, parent, mimeOf(name), name)), "w")
                        }
                        ?: throw ImportProblem(CANT_WRITE)
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                }
                "delete" -> find(op.src ?: continue)?.let { if (!delete(tree, it.id)) throw ImportProblem(CANT_WRITE) }
                else -> Log.w(TAG, "Unknown file operation ${op.op}")
            }
        }
    }

    /** A file copied into another folder (storage without moves), the original removed; the copy's id. */
    private fun copyFile(tree: Uri, doc: Doc, toParent: String): String {
        if (doc.isDir) throw ImportProblem(CANT_WRITE)
        val id = create(tree, toParent, mimeOf(doc.name), doc.name)
        val out = resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id), "w") ?: throw ImportProblem(CANT_WRITE)
        out.use { o -> resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))?.use { it.copyTo(o) } ?: throw ImportProblem(CANT_WRITE) }
        delete(tree, doc.id)
        return id
    }

    /** One of Core's file operations (theme_edit.FileOps). */
    data class FileOp(val op: String, val src: String?, val dst: String?)

    /** Removes a theme folder from the phone. */
    suspend fun remove(theme: LocalTheme): Boolean = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext false
        delete(tree, theme.docId)
    }

    /**
     * Puts a downloaded theme into the themes folder: [paths] (relative to [source]) and the [marker] file.
     * Written into a work folder first and renamed once complete; an update moves the old copy aside,
     * puts the new one in its place, then removes the old one (put back if anything fails before that).
     * Returns the folder name.
     */
    suspend fun install(source: File, paths: List<String>, name: String, marker: String, replacing: LocalTheme?): String = withContext(Dispatchers.IO) {
        val tree = root ?: throw ImportProblem("Choose a folder for themes first")
        val rootId = rootId(tree)
        val taken = children(tree, rootId).map { it.name.lowercase() }.toSet()
        val finalName = replacing?.folder ?: PackageRules.folderName(name, taken)
        val stamp = System.currentTimeMillis()
        val work = "${WORK_PREFIX}new-$stamp"
        val aside = "${WORK_PREFIX}old-$stamp"
        busy += work
        busy += aside
        try {
            val folder = create(tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, work)
            try {
                val dirs = mutableMapOf("" to folder)
                paths.forEach { path ->
                    coroutineContext.ensureActive()
                    val rel = path.split('/')
                    val doc = create(tree, dirOf(tree, dirs, rel.dropLast(1)), mimeOf(rel.last()), rel.last())
                    val out = resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc)) ?: throw ImportProblem(CANT_WRITE)
                    out.use { o -> File(source, path).inputStream().use { it.copyTo(o) } }
                }
                // Without the marker the theme wouldn't show as saved from the server: fail the install
                val doc = create(tree, folder, "application/json", MARKER)
                val out = resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc)) ?: throw ImportProblem(CANT_WRITE)
                out.use { it.write(marker.toByteArray()) }
            } catch (e: Throwable) {
                delete(tree, folder)
                throw e
            }
            if (replacing == null) {
                try {
                    rename(tree, folder, finalName)
                } catch (e: ImportProblem) {
                    delete(tree, folder)
                    throw e
                }
            } else {
                val old = try {
                    rename(tree, replacing.docId, aside)
                } catch (e: ImportProblem) {
                    delete(tree, folder)
                    throw ImportProblem("Couldn't update “${replacing.name}” on this phone; the old version is still there.")
                }
                try {
                    rename(tree, folder, finalName)
                } catch (e: ImportProblem) {
                    runCatching { rename(tree, old, finalName) }
                    delete(tree, folder)
                    throw ImportProblem("Couldn't update “${replacing.name}” on this phone; the old version is still there.")
                }
                // A leftover old copy is a hidden work folder: it doesn't show, and the next listing removes it
                if (!delete(tree, old)) Log.w(TAG, "The old copy of ${replacing.folder} stays until the next listing")
            }
        } finally {
            busy -= work
            busy -= aside
        }
        finalName
    }

    open class ImportProblem(message: String) : Exception(message)

    companion object {
        private const val TAG = "ThemeStore"
        private const val KEY_ROOT = "themeFolder"
        private const val CANT_WRITE = "Couldn't write to the themes folder. Check that it's still there and there's free space."

        /** Work folders (hidden) for imports and downloads in progress; renamed or removed when done. */
        private const val WORK_PREFIX = ".sonorium-"
        private val busy: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** In a theme folder downloaded from a server: theme id, content hash, the server's playback values. */
        const val MARKER = ".sonorium-package.json"
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
