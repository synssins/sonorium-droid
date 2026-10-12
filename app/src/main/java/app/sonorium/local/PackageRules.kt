package app.sonorium.local

import app.sonorium.net.PackageFile
import app.sonorium.net.PackageSummary
import java.security.MessageDigest

/** Theme packages (APP_API.md 10): the content hash, and what a server theme's card offers for this phone. */
object PackageRules {
    /** `"sha256:" + hex SHA-256 of "path\tsize\tsha256\n"` for each file, sorted by path in code-point order. */
    fun contentHash(files: List<PackageFile>): String {
        val lines = files.sortedWith { a, b -> compareCodePoints(a.path, b.path) }
            .joinToString("") { "${it.path}\t${it.size}\t${it.sha256.lowercase()}\n" }
        return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8)))
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return x.size.compareTo(y.size)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** A path from the server is safe to write: relative, "/"-separated, no "..", no hidden names. */
    fun safePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || '\\' in path) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }
    }

    enum class OnPhone { NONE, SAVED, UPDATE }

    /** Whether [pkg] is on this phone already ([local]: the downloaded themes), and current. */
    fun onPhone(pkg: PackageSummary?, local: List<LocalTheme>?): OnPhone {
        val mine = pkg?.let { p -> local?.firstOrNull { it.themeId == p.themeId } } ?: return OnPhone.NONE
        return if (mine.contentHash == pkg.contentHash) OnPhone.SAVED else OnPhone.UPDATE
    }

    /** The menu entry for a server theme, or null when it can't be copied (licence) or the server has no packages. */
    fun action(pkg: PackageSummary?, local: List<LocalTheme>?): String? = when {
        pkg == null || pkg.redistributable == false -> null
        else -> when (onPhone(pkg, local)) {
            OnPhone.NONE -> "Save to phone"
            OnPhone.UPDATE -> "Update on phone"
            OnPhone.SAVED -> null
        }
    }

    /** A folder name for the theme that doesn't clash with [taken] (lower-case names already in the folder). */
    fun folderName(name: String, taken: Set<String>): String {
        val base = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').ifBlank { "Theme" }
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }
}
