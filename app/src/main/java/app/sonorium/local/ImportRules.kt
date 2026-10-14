package app.sonorium.local

/**
 * What a theme zip holds, decided from its entry names before anything is written (pure, unit tested).
 * The server's Export makes one `<folder>/` with the theme's files; a zip of the files themselves (no
 * top folder) is a theme named after the zip.
 */
object ImportRules {
    /** A theme to import: its folder name and each zip entry to copy with its path inside the folder. */
    data class Plan(
        val folderName: String,
        val files: List<Pair<String, String>>,
        /** Other themes in the same zip (top folders), not imported. */
        val skipped: List<String> = emptyList(),
    )

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Problem(val message: String) : Result()
    }

    const val NO_THEME = "This zip has no theme in it (no audio files or metadata.json)"
    const val NOT_A_ZIP = "That file isn't a theme zip"

    /** A zip entry name made safe, or null to leave it out (folders, "..", hidden and macOS extras). */
    fun clean(entry: String): String? {
        val path = entry.replace('\\', '/').trimStart('/')
        if (path.isEmpty() || path.endsWith("/")) return null
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (parts[0] == "__MACOSX") return null
        return path
    }

    /** Files that make a folder a theme: audio at its top or in a group folder, or its metadata.json. */
    fun isTheme(relPaths: List<String>): Boolean = relPaths.any { p ->
        val parts = p.split('/')
        (parts.size <= 2 && ThemeStore.isAudio(parts.last())) || p == "metadata.json"
    }

    /** The plan for a zip with these file entries (in zip order); [zipName] names a zip without a top folder. */
    fun plan(entries: List<String>, zipName: String, existing: Set<String>): Result {
        val files = entries.mapNotNull { e -> clean(e)?.let { e to it } }
        if (files.isEmpty()) return Result.Problem(NO_THEME)
        val top = files.filter { '/' !in it.second }
        val name: String
        val chosen: List<Pair<String, String>>
        var skipped = emptyList<String>()
        if (isTheme(top.map { it.second })) {
            // The theme's files at the top of the zip: everything is one theme
            name = zipName.substringBeforeLast('.').ifBlank { zipName }
            chosen = files
        } else {
            val folders = files.filter { '/' in it.second }.map { it.second.substringBefore('/') }.distinct()
            val themes = folders.filter { f -> isTheme(files.filter { it.second.startsWith("$f/") }.map { it.second.removePrefix("$f/") }) }
            if (themes.isEmpty()) return Result.Problem(NO_THEME)
            name = themes.first()
            skipped = themes.drop(1)
            chosen = files.filter { it.second.startsWith("$name/") }.map { it.first to it.second.removePrefix("$name/") }
        }
        val folder = safeName(name)
        if (folder.lowercase() in existing) {
            return Result.Problem("“$folder” is already on this device. Remove it first to import it again.")
        }
        return Result.Ok(Plan(folder, chosen, skipped))
    }

    /** A folder name without characters the file system refuses. */
    fun safeName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').ifBlank { "Theme" }

    /** The toast after an import. */
    fun importedText(plan: Plan): String = when (plan.skipped.size) {
        0 -> "Imported “${plan.folderName}”"
        1 -> "Imported “${plan.folderName}”. This zip also has “${plan.skipped[0]}”: import one theme per zip."
        else -> "Imported “${plan.folderName}”. This zip also has ${plan.skipped.size} other themes: import one theme per zip."
    }
}
