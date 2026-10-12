package app.sonorium.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this install has the paid features. Test builds grant them locally when asked (no store, no
 * server); a store purchase can replace [purchase] / [restore] behind the same interface.
 */
interface ProEntitlement {
    val isPro: StateFlow<Boolean>
    /** Starts the purchase; true when it was granted. */
    suspend fun purchase(): Boolean
    /** Looks for an earlier purchase; true when found. */
    suspend fun restore(): Boolean
}

/** Test builds: the purchase is a local "yes", kept on the device. */
class LocalProEntitlement(context: Context) : ProEntitlement {
    private val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))
    override val isPro: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun purchase(): Boolean {
        prefs.edit().putBoolean(KEY, true).apply()
        state.value = true
        return true
    }

    override suspend fun restore(): Boolean = state.value

    private companion object {
        const val KEY = "proUnlocked"
    }
}
