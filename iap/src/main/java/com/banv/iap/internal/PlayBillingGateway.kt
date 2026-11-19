package com.banv.iap.internal

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.banv.iap.IapError
import com.banv.iap.IapLogger
import com.banv.iap.IapPurchase
import com.banv.iap.IapResult
import com.banv.iap.ProductQuery
import com.banv.iap.ProductSpec
import com.banv.iap.ProductType
import com.banv.iap.PurchaseOptions
import com.banv.iap.ReplacementMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** [BillingGateway] on Play Billing Library 9.x. All Billing types stay inside this file. */
internal class PlayBillingGateway(
    context: Context,
    private val logger: IapLogger,
) : BillingGateway {

    override var purchasesListener: ((IapResult<List<IapPurchase>>) -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            val update = if (result.isOk) {
                IapResult.Success(purchases.orEmpty().mapNotNull { it.toIapPurchase() })
            } else {
                IapResult.Failure(result.toIapError())
            }
            purchasesListener?.invoke(update)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        // Re-establishes the connection when an API call finds it dropped.
        .enableAutoServiceReconnection()
        .build()

    private val details = ConcurrentHashMap<String, ProductDetails>()

    override val isReady: Boolean get() = client.isReady

    override suspend fun connect(): IapResult<Unit> = suspendCancellableCoroutine { cont ->
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.toUnitResult())
            }

            override fun onBillingServiceDisconnected() {
                // Fires on a later drop too; only the first outcome resumes.
                if (cont.isActive) cont.resume(IapResult.Failure(IapError.ServiceUnavailable))
            }
        })
    }

    override suspend fun queryProducts(specs: List<ProductSpec>): IapResult<ProductQuery> {
        val products = mutableListOf<com.banv.iap.IapProduct>()
        val unavailable = mutableListOf<String>()
        // One query per Play product type.
        for ((playType, group) in specs.groupBy { it.type.playType }) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    group.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it.productId)
                            .setProductType(playType)
                            .build()
                    },
                )
                .build()
            val (result, query) = suspendCancellableCoroutine { cont ->
                client.queryProductDetailsAsync(params) { r, q -> if (cont.isActive) cont.resume(r to q) }
            }
            if (!result.isOk) return IapResult.Failure(result.toIapError())
            val typeById = group.associate { it.productId to it.type }
            query.productDetailsList.forEach { pd ->
                details[pd.productId] = pd
                products += pd.toIapProduct(typeById.getValue(pd.productId))
            }
            query.unfetchedProductList.forEach {
                logger.log(IapLogger.Level.WARN, "Product ${it.productId} unavailable (status ${it.statusCode})", null)
                unavailable += it.productId
            }
        }
        return IapResult.Success(ProductQuery(products, unavailable))
    }

    override fun hasDetails(productId: String): Boolean = details.containsKey(productId)

    override fun launch(activity: Activity, spec: ProductSpec, options: PurchaseOptions): IapResult<Unit> {
        val pd = details[spec.productId] ?: return IapResult.Failure(IapError.ItemUnavailable)
        val offerToken = options.offerToken ?: pd.defaultOfferToken()
        if (spec.type == ProductType.SUBSCRIPTION && offerToken == null) {
            return IapResult.Failure(IapError.DeveloperError("Subscription ${spec.productId} has no offer"))
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(pd)
            .apply { offerToken?.let(::setOfferToken) }
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .apply {
                options.obfuscatedAccountId?.let(::setObfuscatedAccountId)
                options.obfuscatedProfileId?.let(::setObfuscatedProfileId)
                options.subscriptionUpdate?.let { update ->
                    setSubscriptionUpdateParams(
                        BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(update.oldPurchaseToken)
                            .setSubscriptionReplacementMode(update.replacementMode.playMode)
                            .build(),
                    )
                }
            }
            .build()
        return client.launchBillingFlow(activity, params).toUnitResult()
    }

    override suspend fun queryPurchases(): IapResult<List<IapPurchase>> {
        val all = mutableListOf<IapPurchase>()
        for (type in listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            val (result, purchases) = suspendCancellableCoroutine { cont ->
                client.queryPurchasesAsync(params) { r, p -> if (cont.isActive) cont.resume(r to p) }
            }
            if (!result.isOk) return IapResult.Failure(result.toIapError())
            purchases.mapNotNullTo(all) { it.toIapPurchase() }
        }
        return IapResult.Success(all)
    }

    override suspend fun consume(purchaseToken: String): IapResult<Unit> = suspendCancellableCoroutine { cont ->
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.consumeAsync(params) { result, _ -> if (cont.isActive) cont.resume(result.toUnitResult()) }
    }

    override suspend fun acknowledge(purchaseToken: String): IapResult<Unit> = suspendCancellableCoroutine { cont ->
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.acknowledgePurchase(params) { result -> if (cont.isActive) cont.resume(result.toUnitResult()) }
    }

    override fun close() {
        client.endConnection()
    }

    private val ProductType.playType: String
        get() = if (this == ProductType.SUBSCRIPTION) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

    private val ReplacementMode.playMode: Int
        get() = when (this) {
            ReplacementMode.WITH_TIME_PRORATION -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
            ReplacementMode.CHARGE_PRORATED_PRICE -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
            ReplacementMode.CHARGE_FULL_PRICE -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
            ReplacementMode.WITHOUT_PRORATION -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION
            ReplacementMode.DEFERRED -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
        }
}

private val BillingResult.isOk: Boolean get() = responseCode == BillingResponseCode.OK

private fun BillingResult.toUnitResult(): IapResult<Unit> =
    if (isOk) IapResult.Success(Unit) else IapResult.Failure(toIapError())

internal fun BillingResult.toIapError(): IapError = when (responseCode) {
    BillingResponseCode.USER_CANCELED -> IapError.UserCancelled
    BillingResponseCode.ITEM_ALREADY_OWNED -> IapError.ItemAlreadyOwned
    BillingResponseCode.ITEM_UNAVAILABLE -> IapError.ItemUnavailable
    BillingResponseCode.BILLING_UNAVAILABLE,
    BillingResponseCode.FEATURE_NOT_SUPPORTED,
    -> IapError.BillingUnavailable
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.SERVICE_DISCONNECTED,
    BillingResponseCode.SERVICE_TIMEOUT,
    BillingResponseCode.NETWORK_ERROR,
    -> IapError.ServiceUnavailable
    BillingResponseCode.DEVELOPER_ERROR -> IapError.DeveloperError(debugMessage)
    else -> IapError.Unknown(responseCode, debugMessage)
}
