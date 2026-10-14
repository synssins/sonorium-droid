package app.sonorium.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this install has the paid features: bought through Google Play in release builds
 * ([PlayProEntitlement]), a local "yes" in test builds ([LocalProEntitlement]). Which one a build has is chosen
 * by its build type (`Entitlements.create`, in src/debug, src/qa and src/release).
 */
interface ProEntitlement {
    val isPro: StateFlow<Boolean>
    /** The store's price for it, formatted for the user's country (null until known, or in test builds). */
    val price: StateFlow<String?> get() = MutableStateFlow(null)
    /** A purchase is waiting for its payment (e.g. cash at a shop): unlocked once it goes through. */
    val pending: StateFlow<Boolean> get() = MutableStateFlow(false)
    /** A test build's local unlock (it charges nothing). */
    val local: Boolean get() = false
    /** Starts the purchase; true when it was granted. */
    suspend fun purchase(): Boolean
    /** Looks for an earlier purchase; true when found. */
    suspend fun restore(): Boolean
    /** Reads the purchases again (app start and return): a refund takes the features away, a promo code adds them. */
    suspend fun refresh() {}
}

/** Test builds: the purchase is a local "yes", kept on the device. */
class LocalProEntitlement(context: Context) : ProEntitlement {
    private val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))
    override val isPro: StateFlow<Boolean> = state.asStateFlow()
    override val local: Boolean get() = true

    override suspend fun purchase(): Boolean {
        prefs.edit().putBoolean(KEY, true).apply()
        state.value = true
        return true
    }

    override suspend fun restore(): Boolean = state.value

    /** Test builds only: back to free (device tests of what free users see). */
    fun revoke() {
        prefs.edit().putBoolean(KEY, false).apply()
        state.value = false
    }

    private companion object {
        const val KEY = "proUnlocked"
    }
}
