package com.banv.iap.testing

import android.app.Activity
import com.banv.iap.ConnectionState
import com.banv.iap.IapClient
import com.banv.iap.IapError
import com.banv.iap.IapProduct
import com.banv.iap.IapPurchase
import com.banv.iap.IapResult
import com.banv.iap.OneTimeOffer
import com.banv.iap.PricingPhase
import com.banv.iap.ProductQuery
import com.banv.iap.ProductType
import com.banv.iap.PurchaseEvent
import com.banv.iap.PurchaseOptions
import com.banv.iap.PurchaseProcessor
import com.banv.iap.PurchaseState
import com.banv.iap.Recurrence
import com.banv.iap.SubscriptionOffer
import com.banv.iap.Verification
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [IapClient] for unit tests, previews and debug builds: no Play Store needed.
 * Purchases go through [processor] exactly like the real client (verify → grant → finish),
 * so the app's grant logic is exercised. Set [nextOutcome] to script the next purchase.
 */
public class FakeIapClient(
    products: List<IapProduct>,
    private val processor: PurchaseProcessor = TrustAllProcessor(),
) : IapClient {

    public enum class Outcome { PURCHASED, PENDING, CANCELLED, FAILED }

    /** Result of the next [launchPurchase]. */
    public var nextOutcome: Outcome = Outcome.PURCHASED

    private val catalog = products.associateBy { it.productId }
    private val pending = mutableListOf<IapPurchase>()

    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

    private val events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
    override val purchaseEvents: Flow<PurchaseEvent> = events.asSharedFlow()

    private val owned = MutableStateFlow<Set<String>>(emptySet())
    override val ownedProducts: StateFlow<Set<String>> = owned.asStateFlow()

    override suspend fun connect(): IapResult<Unit> {
        state.value = ConnectionState.CONNECTED
        return IapResult.Success(Unit)
    }

    override suspend fun queryProducts(productIds: List<String>?): IapResult<ProductQuery> {
        val ids = productIds ?: catalog.keys.toList()
        return IapResult.Success(
            ProductQuery(
                products = ids.mapNotNull { catalog[it] },
                unavailable = ids.filterNot { it in catalog },
            ),
        )
    }

    override suspend fun launchPurchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions,
    ): IapResult<Unit> {
        val product = catalog[productId] ?: return IapResult.Failure(IapError.ProductNotInCatalog)
        if (product.type != ProductType.CONSUMABLE && productId in owned.value) {
            return IapResult.Failure(IapError.ItemAlreadyOwned)
        }
        when (nextOutcome) {
            Outcome.PURCHASED -> process(fakePurchase(product, PurchaseState.PURCHASED, options))
            Outcome.PENDING -> {
                val purchase = fakePurchase(product, PurchaseState.PENDING, options)
                pending += purchase
                events.emit(PurchaseEvent.Pending(purchase))
            }
            Outcome.CANCELLED -> events.emit(PurchaseEvent.Cancelled)
            Outcome.FAILED -> events.emit(PurchaseEvent.Failed(IapError.ServiceUnavailable))
        }
        return IapResult.Success(Unit)
    }

    /** Simulates Play completing every pending payment; delivered like a restore. */
    public suspend fun completePendingPurchases() {
        val done = pending.map { it.copy(state = PurchaseState.PURCHASED) }
        pending.clear()
        done.forEach { process(it) }
    }

    override suspend fun restorePurchases(): IapResult<List<IapPurchase>> = IapResult.Success(emptyList())

    override fun close() {
        state.value = ConnectionState.CLOSED
    }

    private suspend fun process(purchase: IapPurchase) {
        val verification = runCatching { processor.verify(purchase) }.getOrDefault(Verification.RetryLater)
        if (verification != Verification.Valid) {
            events.emit(PurchaseEvent.Failed(IapError.VerificationFailed, purchase))
            return
        }
        if (!runCatching { processor.grant(purchase) }.getOrDefault(false)) {
            events.emit(PurchaseEvent.Failed(IapError.GrantFailed, purchase))
            return
        }
        if (catalog[purchase.productId]?.type != ProductType.CONSUMABLE) {
            owned.update { it + purchase.productId }
        }
        events.emit(PurchaseEvent.Completed(purchase.copy(isAcknowledged = true)))
    }

    private fun fakePurchase(product: IapProduct, state: PurchaseState, options: PurchaseOptions) = IapPurchase(
        productIds = listOf(product.productId),
        purchaseToken = "fake-token-${UUID.randomUUID()}",
        orderId = "GPA.FAKE-${UUID.randomUUID().toString().take(8)}",
        state = state,
        purchaseTimeMillis = System.currentTimeMillis(),
        quantity = 1,
        isAcknowledged = false,
        isAutoRenewing = product.type == ProductType.SUBSCRIPTION,
        obfuscatedAccountId = options.obfuscatedAccountId,
        obfuscatedProfileId = options.obfuscatedProfileId,
        originalJson = "{}",
        signature = "",
    )

    public companion object {
        /** A one-time product priced [formattedPrice]. */
        public fun inAppProduct(
            productId: String,
            formattedPrice: String,
            type: ProductType = ProductType.CONSUMABLE,
            title: String = productId,
        ): IapProduct = IapProduct(
            productId = productId,
            type = type,
            title = title,
            description = "",
            oneTimeOffers = listOf(OneTimeOffer(null, "fake-offer-$productId", formattedPrice, 0, "USD")),
            subscriptionOffers = emptyList(),
        )

        /** A subscription with one base plan billed every [billingPeriod] (ISO-8601, e.g. "P1M"). */
        public fun subscription(
            productId: String,
            formattedPrice: String,
            basePlanId: String = "monthly",
            billingPeriod: String = "P1M",
            title: String = productId,
        ): IapProduct = IapProduct(
            productId = productId,
            type = ProductType.SUBSCRIPTION,
            title = title,
            description = "",
            oneTimeOffers = emptyList(),
            subscriptionOffers = listOf(
                SubscriptionOffer(
                    basePlanId = basePlanId,
                    offerId = null,
                    offerToken = "fake-offer-$productId-$basePlanId",
                    tags = emptyList(),
                    pricingPhases = listOf(
                        PricingPhase(formattedPrice, 0, "USD", billingPeriod, 0, Recurrence.INFINITE),
                    ),
                ),
            ),
        )
    }
}
