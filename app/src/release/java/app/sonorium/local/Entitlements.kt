package app.sonorium.local

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Release builds: Pro is bought through Google Play; there is no local unlock. */
object Entitlements {
    fun create(context: Context, scope: CoroutineScope, activity: () -> Activity?): ProEntitlement =
        PlayProEntitlement.create(context, scope, activity)
}
