package app.sonorium.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The themes the app ships with (assets/starter-themes, the same ones the server bundles), copied
 * into the themes folder once, so a new install has something to play with no server. A theme
 * removed afterwards stays removed; a folder already holding one of those names is left alone.
 */
object StarterThemes {
    private const val ASSETS = "starter-themes"
    private const val KEY = "starterThemesInstalled"

    /** Copies the starter themes into the chosen themes folder the first time; true when it did. */
    suspend fun installOnce(context: Context, store: ThemeStore): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return@withContext false
        val tree = store.root ?: return@withContext false
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val existing = children(context, tree, rootId).map { it.lowercase() }.toSet()
        val themes = context.assets.list(ASSETS).orEmpty().sorted()
        var copied = false
        for (theme in themes) {
            if (theme.lowercase() in existing) continue
            try {
                val folder = create(context, tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, theme)
                for (file in context.assets.list("$ASSETS/$theme").orEmpty()) {
                    val doc = create(context, tree, folder, ThemeStore.mimeOf(file), file)
                    resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc))?.use { out ->
                        context.assets.open("$ASSETS/$theme/$file").use { it.copyTo(out) }
                    }
                }
                copied = true
            } catch (e: Exception) {
                Log.w("StarterThemes", "Couldn't add $theme", e)
            }
        }
        prefs.edit().putBoolean(KEY, true).apply()
        copied
    }

    private fun children(context: Context, tree: Uri, parentId: String): List<String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) ?: "") }
        }.orEmpty()
    }

    private fun create(context: Context, tree: Uri, parentId: String, mime: String, name: String): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: throw IllegalStateException("Couldn't write to the themes folder")
        return DocumentsContract.getDocumentId(uri)
    }
}
