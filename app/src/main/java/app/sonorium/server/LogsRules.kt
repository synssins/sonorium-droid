package app.sonorium.server

/** Settings › Logs (native-api.md Phase 3; `logs.js`). */
object LogsRules {
    data class Entry(val time: String, val level: String, val source: String, val message: String, val seq: Long)

    val FILTERS = listOf("All", "Info", "Warnings", "Errors", "Playback")
    const val KEEP = 2000

    fun rank(level: String): Int = when (level.uppercase()) {
        "DEBUG" -> 0
        "INFO" -> 1
        "WARNING" -> 2
        "ERROR" -> 3
        "CRITICAL" -> 4
        else -> 1
    }

    /** `visible()`: the filter, then the search (message or source, any case). */
    fun visible(e: Entry, filter: String, search: String): Boolean {
        val passes = when (filter) {
            "All" -> true
            "Info" -> rank(e.level) >= 1
            "Warnings" -> rank(e.level) >= 2
            "Errors" -> rank(e.level) >= 3
            "Playback" -> e.message.contains("Playback:")
            else -> true
        }
        val q = search.trim().lowercase()
        return passes && (q.isEmpty() || e.message.lowercase().contains(q) || e.source.lowercase().contains(q))
    }

    /** Copy: `<time> <LEVEL padded to 8> <source>: <message>`. */
    fun line(e: Entry): String = "${e.time} ${e.level.uppercase().padEnd(8)} ${e.source}: ${e.message}"

    /** Appends and keeps the last [KEEP]. */
    fun append(old: List<Entry>, new: List<Entry>): List<Entry> = (old + new).takeLast(KEEP)

    fun meta(version: String, install: String, level: String): String =
        listOf(version, install.takeIf { it.isNotEmpty() }, "log level: $level").filterNotNull().joinToString(" · ")
}
