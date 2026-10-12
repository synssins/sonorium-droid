package app.sonorium.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.serverData by preferencesDataStore(name = "servers")

/** Linked servers and their tokens. Tokens are encrypted and never logged. */
class ServerStore(private val context: Context, private val cipher: TokenCipher = TokenCipher()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ServerRecord.serializer())

    val servers: Flow<List<ServerRecord>> = context.serverData.data.map { prefs ->
        prefs[SERVERS]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }

    val currentId: Flow<String?> = context.serverData.data.map { it[CURRENT] }

    suspend fun find(id: String): ServerRecord? = servers.first().firstOrNull { it.id == id }

    suspend fun save(record: ServerRecord, token: String) {
        context.serverData.edit { prefs ->
            val list = decode(prefs[SERVERS]).filter { it.id != record.id } + record
            prefs[SERVERS] = json.encodeToString(serializer, list)
            prefs[tokenKey(record.id)] = cipher.encrypt(token)
            prefs[CURRENT] = record.id
        }
    }

    suspend fun update(record: ServerRecord) {
        context.serverData.edit { prefs ->
            prefs[SERVERS] = json.encodeToString(serializer, decode(prefs[SERVERS]).map { if (it.id == record.id) record else it })
        }
    }

    suspend fun rename(id: String, name: String?) {
        find(id)?.let { update(it.copy(customName = name?.trim()?.takeIf { n -> n.isNotEmpty() })) }
    }

    suspend fun select(id: String) {
        context.serverData.edit { it[CURRENT] = id }
    }

    suspend fun token(id: String): String? =
        context.serverData.data.first()[tokenKey(id)]?.let(cipher::decrypt)

    /** Forget a server and its token (unlinking, or a 401 `pair`). */
    suspend fun remove(id: String) {
        context.serverData.edit { prefs ->
            val list = decode(prefs[SERVERS]).filter { it.id != id }
            prefs[SERVERS] = json.encodeToString(serializer, list)
            prefs.remove(tokenKey(id))
            if (prefs[CURRENT] == id) {
                list.firstOrNull()?.let { prefs[CURRENT] = it.id } ?: prefs.remove(CURRENT)
            }
        }
    }

    private fun decode(raw: String?): List<ServerRecord> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        val CURRENT = stringPreferencesKey("current")
        fun tokenKey(id: String) = stringPreferencesKey("token:$id")
    }
}
