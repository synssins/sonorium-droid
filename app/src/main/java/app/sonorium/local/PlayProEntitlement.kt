package app.sonorium.local

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A purchase as the store reports it. */
data class StorePurchase(
    val products: List<String>,
    val state: State,
    val token: String,
    val acknowledged: Boolean,
) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/** What a purchase attempt came back with. */
enum class StoreAnswer { OK, CANCELED, ALREADY_OWNED, UNAVAILABLE, ERROR }

/**
 * The store, as [PlayProEntitlement] needs it: Google Play Billing in the app ([PlayBillingGateway]), a fake in
 * the unit tests. Purchases made in the store's own screens (the purchase flow, a promo code redeemed in the
 * Play Store) arrive through [onPurchases].
 */
interface BillingGateway {
    /** Called with purchases as they change (from the purchase flow or the store). */
    var onPurchases: ((StoreAnswer, List<StorePurchase>) -> Unit)?

    /** Connected (or connecting worked); false when the store can't be reached. */
    suspend fun connect(): Boolean

    /** The product's price, formatted (null when the store doesn't have it). */
    suspend fun price(productId: String): String?

    /** This account's purchases now, or null when the store couldn't say. */
    suspend fun purchases(): List<StorePurchase>?

    /** Opens the store's purchase screen for [productId] over [activity]. */
    suspend fun launch(activity: Activity, productId: String): StoreAnswer

    /** Tells the store the purchase was granted (else Play refunds it after three days). */
    suspend fun acknowledge(token: String): Boolean
}

/**
 * Pro bought through Google Play (product [PRODUCT]): the purchase flow; acknowledging; pending payments;
 * refunds and promo codes through [refresh]. The last answer is kept on the device, so Pro stays while the
 * store can't be reached.
 */
class PlayProEntitlement(
    private val gateway: BillingGateway,
    private val cache: Cache,
    private val activity: () -> Activity?,
    /** Where purchases that arrive by themselves (a promo code, a payment that went through) are taken in. */
    private val scope: kotlinx.coroutines.CoroutineScope,
) : ProEntitlement {
    /** Where the last answer is kept. */
    interface Cache {
        var pro: Boolean
    }

    private val state = MutableStateFlow(cache.pro)
    override val isPro: StateFlow<Boolean> = state.asStateFlow()
    private val _price = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> = _price.asStateFlow()
    private val _pending = MutableStateFlow(false)
    override val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** How the last purchase attempt ended (the screen says when the store couldn't be reached). */
    @Volatile var lastAnswer: StoreAnswer? = null
        private set

    private val lock = Mutex()
    private var waiting: CompletableDeferred<Pair<StoreAnswer, List<StorePurchase>>>? = null

    init {
        gateway.onPurchases = { answer, purchases ->
            val w = waiting
            if (w != null && !w.isCompleted) w.complete(answer to purchases)
            else if (answer == StoreAnswer.OK) scope.launch { lock.withLock { apply(purchases, complete = false) } }
        }
    }

    override suspend fun refresh() {
        lock.withLock {
            if (!gateway.connect()) return
            if (_price.value == null) _price.value = runCatching { gateway.price(PRODUCT) }.getOrNull()
            val owned = gateway.purchases() ?: return // the store couldn't say: keep what's known
            apply(owned, complete = true)
        }
    }

    /**
     * Takes the purchases in: granted (acknowledged first when needed), pending, or, when [complete] is the
     * whole list, none (refunded or never bought).
     */
    private suspend fun apply(purchases: List<StorePurchase>, complete: Boolean) {
        val mine = purchases.filter { PRODUCT in it.products }
        val bought = mine.firstOrNull { it.state == StorePurchase.State.PURCHASED }
        _pending.value = bought == null && mine.any { it.state == StorePurchase.State.PENDING }
        when {
            bought != null -> {
                if (!bought.acknowledged && !gateway.acknowledge(bought.token)) Log.w(TAG, "Acknowledging the purchase failed; tried again next time")
                set(true)
            }
            complete -> set(false)
        }
    }

    private fun set(pro: Boolean) {
        cache.pro = pro
        state.value = pro
    }

    override suspend fun purchase(): Boolean {
        lastAnswer = null
        val host = activity() ?: return false
        if (!gateway.connect()) {
            lastAnswer = StoreAnswer.UNAVAILABLE
            return false
        }
        val wait = CompletableDeferred<Pair<StoreAnswer, List<StorePurchase>>>()
        waiting = wait
        try {
            val launched = gateway.launch(host, PRODUCT)
            lastAnswer = launched
            when (launched) {
                StoreAnswer.OK -> Unit
                StoreAnswer.ALREADY_OWNED -> return restore()
                else -> return false
            }
            val (answer, purchases) = wait.await()
            lastAnswer = answer
            return when (answer) {
                StoreAnswer.OK -> {
                    lock.withLock { apply(purchases, complete = false) }
                    state.value
                }
                StoreAnswer.ALREADY_OWNED -> restore()
                else -> false
            }
        } finally {
            waiting = null
        }
    }

    override suspend fun restore(): Boolean {
        refresh()
        return state.value
    }

    companion object {
        /** The Play Console product: a one-time purchase. */
        const val PRODUCT = "pro"
        private const val TAG = "PlayPro"

        /** The app's entitlement: Google Play, the answer kept in the app's preferences. */
        fun create(context: Context, scope: kotlinx.coroutines.CoroutineScope, activity: () -> Activity?): PlayProEntitlement {
            val prefs = context.getSharedPreferences("sonorium", Context.MODE_PRIVATE)
            val cache = object : Cache {
                override var pro: Boolean
                    get() = prefs.getBoolean("playPro", false)
                    set(v) = prefs.edit().putBoolean("playPro", v).apply()
            }
            return PlayProEntitlement(PlayBillingGateway(context.applicationContext), cache, activity, scope)
        }
    }
}
