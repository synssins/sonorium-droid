package app.sonorium.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * The app's own themes folder as documents (the default themes folder, no permission to ask): a folder in the
 * app's storage on this device (`Android/data/<app>/files/Sonorium`, removed with the app), offered in the same
 * document form as a folder the user picks, so [ThemeStore] works the same on both.
 *
 * Document ids are `<base>:<path>` with `base` = [BASE_THEMES] (the themes folder's parent) or, for device
 * tests, [BASE_TEST] (a folder in the app's cache standing in for one the user would pick).
 */
class AppFolderProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    private fun baseDir(base: String): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        return when (base) {
            BASE_THEMES -> appStorage(ctx)
            BASE_TEST -> File(ctx.cacheDir, "folder-test").apply { mkdirs() }
            else -> throw FileNotFoundException("Unknown base $base")
        }
    }

    private fun fileOf(docId: String): File {
        val base = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val root = baseDir(base).canonicalFile
        val f = (if (rel.isEmpty()) root else File(root, rel)).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) throw FileNotFoundException("Outside the folder: $docId")
        return f
    }

    private fun idOf(base: String, file: File): String {
        val root = baseDir(base).canonicalFile
        val rel = file.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
        return "$base:$rel"
    }

    private fun row(c: MatrixCursor, docId: String, f: File) {
        val dir = f.isDirectory
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, f.name)
            add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mimeOf(f.name))
            add(Document.COLUMN_SIZE, if (dir) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(
                Document.COLUMN_FLAGS,
                (if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
                    Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE,
            )
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        // Not offered as a root in the system picker: the app reaches it by its own URIs
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = fileOf(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).also { row(it, documentId, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val base = parentDocumentId.substringBefore(':')
        val dir = fileOf(parentDocumentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).also { c ->
            dir.listFiles()?.forEach { f -> row(c, idOf(base, f), f) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fileOf(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val base = parentDocumentId.substringBefore(':')
        val dir = fileOf(parentDocumentId)
        val f = File(dir, displayName)
        if (f.exists()) throw FileNotFoundException("Already there: $displayName")
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
        if (!ok) throw FileNotFoundException("Couldn't create $displayName")
        return idOf(base, f)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw FileNotFoundException("Couldn't delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val base = documentId.substringBefore(':')
        val f = fileOf(documentId)
        val to = File(f.parentFile, displayName)
        if (!f.renameTo(to)) throw FileNotFoundException("Couldn't rename $documentId")
        return idOf(base, to)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val base = targetParentDocumentId.substringBefore(':')
        val f = fileOf(sourceDocumentId)
        val to = File(fileOf(targetParentDocumentId), f.name)
        if (!f.renameTo(to)) throw FileNotFoundException("Couldn't move $sourceDocumentId")
        return idOf(base, to)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.substringBefore(':') == parentDocumentId.substringBefore(':') &&
            (fileOf(documentId).path + File.separator).startsWith(fileOf(parentDocumentId).path + File.separator)

    companion object {
        const val BASE_THEMES = "app"
        const val BASE_TEST = "test"
        /** The default folder's name, as in a folder the user picks. */
        const val FOLDER = "Sonorium"

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: android.content.Context) = context.packageName + ".folders"

        /** Where the default folder's parent is: the app's external files, else its internal files. */
        fun appStorage(context: android.content.Context): File =
            (context.getExternalFilesDir(null) ?: context.filesDir).apply { mkdirs() }

        /** The default themes folder as a tree (its parent) and the folder's document id, made if needed. */
        fun defaultFolder(context: android.content.Context): Pair<android.net.Uri, String> {
            File(appStorage(context), FOLDER).mkdirs()
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri(authority(context), "$BASE_THEMES:")
            return tree to "$BASE_THEMES:$FOLDER"
        }

        private fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
