package app.sonorium.local

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Test builds: Pro unlocks locally, charging nothing (release builds buy it through Google Play). */
object Entitlements {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, scope: CoroutineScope, activity: () -> Activity?): ProEntitlement = LocalProEntitlement(context)
}
