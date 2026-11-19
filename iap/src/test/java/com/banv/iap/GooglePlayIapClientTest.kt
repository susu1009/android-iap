package com.banv.iap

import android.app.Activity
import com.banv.iap.internal.BillingGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayIapClientTest {

    private val gems = ProductSpec("gems_100", ProductType.CONSUMABLE)
    private val noAds = ProductSpec("remove_ads", ProductType.NON_CONSUMABLE)
    private val premium = ProductSpec("premium", ProductType.SUBSCRIPTION)

    private val gateway = FakeGateway()
    private val processor = RecordingProcessor()
    private val activity = Activity()

    private fun TestScope.newClient(): Pair<GooglePlayIapClient, List<PurchaseEvent>> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = GooglePlayIapClient(
            gateway = gateway,
            catalog = { listOf(gems, noAds, premium) },
            processor = processor,
            logger = IapLogger.NONE,
            mainDispatcher = dispatcher,
            backgroundDispatcher = dispatcher,
        )
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch(dispatcher) { client.purchaseEvents.collect { events += it } }
        return client to events
    }

    @Test
    fun consumable_isVerifiedGrantedThenConsumed() = runTest {
        val (client, events) = newClient()
        client.launchPurchase(activity, gems.productId)
        gateway.deliver(purchase(gems.productId, "t1"))

        assertEquals(listOf("verify:t1", "grant:t1"), processor.calls)
        assertEquals(listOf("consume:t1"), gateway.finished)
        assertTrue(events.single() is PurchaseEvent.Completed)
        assertTrue(client.ownedProducts.value.isEmpty())
    }

    @Test
    fun nonConsumable_isAcknowledgedAndOwned() = runTest {
        val (client, events) = newClient()
        gateway.deliver(purchase(noAds.productId, "t1"))

        assertEquals(listOf("ack:t1"), gateway.finished)
        assertEquals(setOf(noAds.productId), client.ownedProducts.value)
        assertTrue((events.single() as PurchaseEvent.Completed).purchase.isAcknowledged)
    }

    @Test
    fun invalidVerification_grantsAndFinishesNothing() = runTest {
        val (_, events) = newClient()
        processor.verification = Verification.Invalid
        gateway.deliver(purchase(gems.productId, "t1"))

        assertEquals(listOf("verify:t1"), processor.calls)
        assertTrue(gateway.finished.isEmpty())
        assertEquals(IapError.VerificationFailed, (events.single() as PurchaseEvent.Failed).error)
    }

    @Test
    fun failedGrant_isRetriedByRestore() = runTest {
        val (client, events) = newClient()
        processor.grantResult = false
        val p = purchase(gems.productId, "t1")
        gateway.deliver(p)
        assertTrue(gateway.finished.isEmpty())
        assertEquals(IapError.GrantFailed, (events.single() as PurchaseEvent.Failed).error)

        processor.grantResult = true
        gateway.purchases = listOf(p)
        client.restorePurchases()

        assertEquals(listOf("consume:t1"), gateway.finished)
        assertTrue(events.last() is PurchaseEvent.Completed)
    }

    @Test
    fun restore_skipsAlreadyAcknowledged_andRebuildsOwned() = runTest {
        val (client, events) = newClient()
        gateway.purchases = listOf(purchase(premium.productId, "t1", acknowledged = true))

        val restored = client.restorePurchases()

        assertTrue(processor.calls.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(setOf(premium.productId), client.ownedProducts.value)
        assertEquals(1, restored.getOrNull()?.size)

        gateway.purchases = emptyList() // subscription expired
        client.restorePurchases()
        assertTrue(client.ownedProducts.value.isEmpty())
    }

    @Test
    fun pending_isReportedOnce_thenCompletedWhenPaid() = runTest {
        val (client, events) = newClient()
        val pending = purchase(gems.productId, "t1", state = PurchaseState.PENDING)
        gateway.deliver(pending)
        gateway.purchases = listOf(pending)
        client.restorePurchases()
        client.restorePurchases()
        assertEquals(1, events.count { it is PurchaseEvent.Pending })
        assertTrue(processor.calls.isEmpty())

        gateway.purchases = listOf(pending.copy(state = PurchaseState.PURCHASED))
        client.restorePurchases()
        assertEquals(listOf("consume:t1"), gateway.finished)
        assertTrue(events.last() is PurchaseEvent.Completed)
    }

    @Test
    fun restoreFailures_emitNoEvents() = runTest {
        val (client, events) = newClient()
        processor.verification = Verification.RetryLater
        gateway.purchases = listOf(purchase(gems.productId, "t1"))
        client.restorePurchases()
        assertTrue(events.isEmpty())
        assertTrue(gateway.finished.isEmpty())
    }

    @Test
    fun userCancel_emitsCancelled() = runTest {
        val (_, events) = newClient()
        gateway.purchasesListener?.invoke(IapResult.Failure(IapError.UserCancelled))
        assertEquals(PurchaseEvent.Cancelled, events.single())
    }

    @Test
    fun alreadyOwned_onLaunch_finishesOldPurchaseFirst() = runTest {
        val (client, _) = newClient()
        gateway.launchResult = IapResult.Failure(IapError.ItemAlreadyOwned)
        gateway.purchases = listOf(purchase(gems.productId, "old"))

        val result = client.launchPurchase(activity, gems.productId)

        assertEquals(IapResult.Failure(IapError.ItemAlreadyOwned), result)
        assertEquals(listOf("consume:old"), gateway.finished)
    }

    @Test
    fun launch_unknownProduct_failsWithoutPlay() = runTest {
        val (client, _) = newClient()
        assertEquals(IapResult.Failure(IapError.ProductNotInCatalog), client.launchPurchase(activity, "nope"))
        assertEquals(0, gateway.launches)
    }

    @Test
    fun launch_queriesDetailsFirstWhenNotCached() = runTest {
        val (client, _) = newClient()
        client.launchPurchase(activity, premium.productId)
        assertEquals(listOf(listOf(premium)), gateway.queries)
        assertEquals(1, gateway.launches)
    }

    @Test
    fun connect_retriesTransientFailure() = runTest {
        val (client, _) = newClient()
        gateway.ready = false
        gateway.connectResults += IapResult.Failure(IapError.ServiceUnavailable)
        gateway.connectResults += IapResult.Success(Unit)

        assertEquals(IapResult.Success(Unit), client.connect())
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
        assertEquals(2, gateway.connects)
    }

    @Test
    fun connect_doesNotRetryBillingUnavailable() = runTest {
        val (client, _) = newClient()
        gateway.ready = false
        gateway.connectResults += IapResult.Failure(IapError.BillingUnavailable)

        assertEquals(IapResult.Failure(IapError.BillingUnavailable), client.connect())
        assertEquals(1, gateway.connects)
    }

    @Test
    fun queryProducts_reportsIdsMissingFromCatalogAsUnavailable() = runTest {
        val (client, _) = newClient()
        val query = client.queryProducts(listOf(gems.productId, "nope")).getOrNull()!!
        assertEquals(listOf(gems.productId), query.products.map { it.productId })
        assertEquals(listOf("nope"), query.unavailable)
    }

    // ---- fakes --------------------------------------------------------------------------

    private fun purchase(
        productId: String,
        token: String,
        state: PurchaseState = PurchaseState.PURCHASED,
        acknowledged: Boolean = false,
    ) = IapPurchase(
        listOf(productId), token, "GPA.$token", state, 0L, 1, acknowledged, false, null, null, "{}", "",
    )

    private class RecordingProcessor : PurchaseProcessor {
        val calls = mutableListOf<String>()
        var verification: Verification = Verification.Valid
        var grantResult = true

        override suspend fun verify(purchase: IapPurchase): Verification {
            calls += "verify:${purchase.purchaseToken}"
            return verification
        }

        override suspend fun grant(purchase: IapPurchase): Boolean {
            calls += "grant:${purchase.purchaseToken}"
            return grantResult
        }
    }

    private class FakeGateway : BillingGateway {
        override var purchasesListener: ((IapResult<List<IapPurchase>>) -> Unit)? = null
        var ready = true
        override val isReady: Boolean get() = ready
        val connectResults = ArrayDeque<IapResult<Unit>>()
        var connects = 0
        var purchases: List<IapPurchase> = emptyList()
        var launchResult: IapResult<Unit> = IapResult.Success(Unit)
        var launches = 0
        val queries = mutableListOf<List<ProductSpec>>()
        val finished = mutableListOf<String>()
        private val cached = mutableSetOf<String>()

        fun deliver(vararg p: IapPurchase) = purchasesListener!!.invoke(IapResult.Success(p.toList()))

        override suspend fun connect(): IapResult<Unit> {
            connects++
            return (connectResults.removeFirstOrNull() ?: IapResult.Success(Unit)).also {
                if (it is IapResult.Success) ready = true
            }
        }

        override suspend fun queryProducts(specs: List<ProductSpec>): IapResult<ProductQuery> {
            queries += specs
            cached += specs.map { it.productId }
            return IapResult.Success(
                ProductQuery(specs.map { IapProduct(it.productId, it.type, it.productId, "", emptyList(), emptyList()) }, emptyList()),
            )
        }

        override fun hasDetails(productId: String) = productId in cached

        override fun launch(activity: Activity, spec: ProductSpec, options: PurchaseOptions): IapResult<Unit> {
            launches++
            return launchResult
        }

        override suspend fun queryPurchases(): IapResult<List<IapPurchase>> = IapResult.Success(purchases)

        override suspend fun consume(purchaseToken: String): IapResult<Unit> {
            finished += "consume:$purchaseToken"
            return IapResult.Success(Unit)
        }

        override suspend fun acknowledge(purchaseToken: String): IapResult<Unit> {
            finished += "ack:$purchaseToken"
            return IapResult.Success(Unit)
        }

        override fun close() = Unit
    }
}
