package app.sonorium.local

import android.app.Activity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pro through Google Play, against a fake store: purchase, acknowledge, pending, refund, promo code, offline. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayProEntitlementTest {
    private class FakeStore : BillingGateway {
        override var onPurchases: ((StoreAnswer, List<StorePurchase>) -> Unit)? = null
        var reachable = true
        var owned: List<StorePurchase>? = emptyList()
        var priceText: String? = "€4.99"
        var launchAnswer = StoreAnswer.OK
        /** What the purchase screen answers through onPurchases, after launch. */
        var flowAnswer: Pair<StoreAnswer, List<StorePurchase>>? = null
        val acknowledged = mutableListOf<String>()
        var launches = 0

        override suspend fun connect() = reachable
        override suspend fun price(productId: String) = priceText
        override suspend fun purchases() = if (reachable) owned else null
        override suspend fun launch(activity: Activity, productId: String): StoreAnswer {
            launches++
            if (launchAnswer == StoreAnswer.OK) flowAnswer?.let { (a, p) -> onPurchases?.invoke(a, p) }
            return launchAnswer
        }
        override suspend fun acknowledge(token: String): Boolean {
            acknowledged += token
            return true
        }
    }

    private class Memory(override var pro: Boolean = false) : PlayProEntitlement.Cache

    private fun bought(token: String = "t1", acked: Boolean = false) = StorePurchase(listOf("pro"), StorePurchase.State.PURCHASED, token, acked)
    private fun pending(token: String = "p1") = StorePurchase(listOf("pro"), StorePurchase.State.PENDING, token, false)

    // An Activity is only passed through to the store; the fake never touches it
    private val activity: Activity? = null
    private fun entitlement(store: FakeStore, cache: Memory, scope: TestScope, host: Boolean = true) =
        PlayProEntitlement(store, cache, { if (host) activity ?: android.app.Activity() else null }, scope)

    @Test fun `a purchase is acknowledged and unlocks, and is kept`() = runTest {
        val store = FakeStore().apply { flowAnswer = StoreAnswer.OK to listOf(bought()) }
        val cache = Memory()
        val pro = entitlement(store, cache, this)
        assertTrue(pro.purchase())
        assertTrue(pro.isPro.value)
        assertEquals(listOf("t1"), store.acknowledged)
        assertTrue("Not kept on the device", cache.pro)
        assertEquals(StoreAnswer.OK, pro.lastAnswer)
    }

    @Test fun `cancelling unlocks nothing`() = runTest {
        val store = FakeStore().apply { flowAnswer = StoreAnswer.CANCELED to emptyList() }
        val pro = entitlement(store, Memory(), this)
        assertFalse(pro.purchase())
        assertFalse(pro.isPro.value)
        assertEquals(StoreAnswer.CANCELED, pro.lastAnswer)
    }

    @Test fun `a pending payment unlocks once it goes through`() = runTest {
        val store = FakeStore().apply { flowAnswer = StoreAnswer.OK to listOf(pending()) }
        val pro = entitlement(store, Memory(), this)
        assertFalse(pro.purchase())
        assertTrue(pro.pending.value)
        assertFalse(pro.isPro.value)
        assertTrue("A pending purchase was acknowledged", store.acknowledged.isEmpty())
        // The payment goes through while the app is open: the store says so by itself
        store.onPurchases!!.invoke(StoreAnswer.OK, listOf(bought("p1")))
        advanceUntilIdle()
        assertTrue(pro.isPro.value)
        assertFalse(pro.pending.value)
        assertEquals(listOf("p1"), store.acknowledged)
    }

    @Test fun `already owned is restored`() = runTest {
        val store = FakeStore().apply { launchAnswer = StoreAnswer.ALREADY_OWNED; owned = listOf(bought(acked = true)) }
        val pro = entitlement(store, Memory(), this)
        assertTrue(pro.purchase())
        assertTrue("An acknowledged purchase was acknowledged again", store.acknowledged.isEmpty())
    }

    @Test fun `restore finds an earlier purchase, or none`() = runTest {
        val store = FakeStore()
        val pro = entitlement(store, Memory(), this)
        assertFalse(pro.restore())
        store.owned = listOf(bought(acked = true))
        assertTrue(pro.restore())
    }

    @Test fun `a refund takes Pro away`() = runTest {
        val store = FakeStore().apply { owned = emptyList() }
        val cache = Memory(pro = true)
        val pro = entitlement(store, cache, this)
        assertTrue("The kept answer isn't used at start", pro.isPro.value)
        pro.refresh()
        assertFalse(pro.isPro.value)
        assertFalse(cache.pro)
    }

    @Test fun `a promo code redeemed in the store unlocks on the next check`() = runTest {
        val store = FakeStore()
        val pro = entitlement(store, Memory(), this)
        pro.refresh()
        assertFalse(pro.isPro.value)
        store.owned = listOf(bought("promo"))
        pro.refresh()
        assertTrue(pro.isPro.value)
        assertEquals(listOf("promo"), store.acknowledged)
    }

    @Test fun `offline keeps the last answer`() = runTest {
        val store = FakeStore().apply { reachable = false }
        val pro = entitlement(store, Memory(pro = true), this)
        pro.refresh()
        assertTrue(pro.isPro.value)
        assertFalse(pro.purchase())
        assertEquals(StoreAnswer.UNAVAILABLE, pro.lastAnswer)
    }

    @Test fun `the price comes from the store`() = runTest {
        val store = FakeStore()
        val pro = entitlement(store, Memory(), this)
        assertNull(pro.price.value)
        pro.refresh()
        assertEquals("€4.99", pro.price.value)
    }

    @Test fun `other products don't unlock`() = runTest {
        val store = FakeStore().apply { owned = listOf(StorePurchase(listOf("tip"), StorePurchase.State.PURCHASED, "x", true)) }
        val pro = entitlement(store, Memory(), this)
        pro.refresh()
        assertFalse(pro.isPro.value)
    }

    @Test fun `no screen to show the store on, no purchase`() = runTest {
        val store = FakeStore()
        val pro = entitlement(store, Memory(), this, host = false)
        val r = async { pro.purchase() }
        assertFalse(r.await())
        assertEquals(0, store.launches)
    }
}
