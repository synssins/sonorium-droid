package app.sonorium.local

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Google Play Billing behind [BillingGateway]: one-time products, pending purchases allowed. */
class PlayBillingGateway(context: Context) : BillingGateway {
    override var onPurchases: ((StoreAnswer, List<StorePurchase>) -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchases?.invoke(answer(result), purchases.orEmpty().map(::mine)) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private var details: ProductDetails? = null

    override suspend fun connect(): Boolean {
        if (client.isReady) return true
        return withTimeoutOrNull(CONNECT_MS) {
            suspendCancellableCoroutine { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        } ?: false
    }

    private suspend fun productDetails(productId: String): ProductDetails? {
        details?.takeIf { it.productId == productId }?.let { return it }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build()),
        ).build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                val d = if (result.responseCode == BillingClient.BillingResponseCode.OK) found.productDetailsList.firstOrNull() else null
                if (d == null) Log.w(TAG, "No product details for $productId: ${result.responseCode} ${result.debugMessage}")
                if (cont.isActive) cont.resume(d)
            }
        }?.also { details = it }
    }

    override suspend fun price(productId: String): String? = productDetails(productId)?.oneTimePurchaseOfferDetails?.formattedPrice

    override suspend fun purchases(): List<StorePurchase>? = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list ->
            if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list.map(::mine) else null)
        }
    }

    override suspend fun launch(activity: Activity, productId: String): StoreAnswer {
        val d = productDetails(productId) ?: return StoreAnswer.UNAVAILABLE
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        return answer(result)
    }

    override suspend fun acknowledge(token: String): Boolean = suspendCancellableCoroutine { cont ->
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
            if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    private fun mine(p: Purchase) = StorePurchase(
        products = p.products,
        state = when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> StorePurchase.State.PURCHASED
            Purchase.PurchaseState.PENDING -> StorePurchase.State.PENDING
            else -> StorePurchase.State.OTHER
        },
        token = p.purchaseToken,
        acknowledged = p.isAcknowledged,
    )

    private fun answer(result: BillingResult) = when (result.responseCode) {
        BillingClient.BillingResponseCode.OK -> StoreAnswer.OK
        BillingClient.BillingResponseCode.USER_CANCELED -> StoreAnswer.CANCELED
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> StoreAnswer.ALREADY_OWNED
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> StoreAnswer.UNAVAILABLE
        else -> StoreAnswer.ERROR
    }

    private companion object {
        const val TAG = "PlayBilling"
        const val CONNECT_MS = 10_000L
    }
}
