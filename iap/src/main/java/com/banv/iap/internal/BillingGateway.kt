package com.banv.iap.internal

import android.app.Activity
import com.banv.iap.IapPurchase
import com.banv.iap.IapResult
import com.banv.iap.ProductQuery
import com.banv.iap.ProductSpec
import com.banv.iap.PurchaseOptions

/**
 * The only seam between the purchase logic and Play Billing. Everything here speaks the
 * library's own types, so [com.banv.iap.GooglePlayIapClient] is unit-testable without Play
 * and a Billing major-version bump only touches [PlayBillingGateway].
 */
internal interface BillingGateway {
    /** Receives Play's purchase-flow callbacks. Set once, before [connect]. */
    var purchasesListener: ((IapResult<List<IapPurchase>>) -> Unit)?

    val isReady: Boolean

    suspend fun connect(): IapResult<Unit>

    /** Queries [specs] (any mix of types). Details are cached for [launch]. */
    suspend fun queryProducts(specs: List<ProductSpec>): IapResult<ProductQuery>

    /** True when [productId]'s details are cached from an earlier [queryProducts]. */
    fun hasDetails(productId: String): Boolean

    /** Must be called on the main thread. */
    fun launch(activity: Activity, spec: ProductSpec, options: PurchaseOptions): IapResult<Unit>

    /** Current in-app purchases and active subscriptions. */
    suspend fun queryPurchases(): IapResult<List<IapPurchase>>

    suspend fun consume(purchaseToken: String): IapResult<Unit>

    suspend fun acknowledge(purchaseToken: String): IapResult<Unit>

    fun close()
}
