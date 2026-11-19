package com.banv.iap

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app purchases. Create one instance per app and keep it for the app's lifetime.
 * All functions are main-safe.
 */
public interface IapClient {
    public val connectionState: StateFlow<ConnectionState>

    /**
     * Outcomes of purchases: from [launchPurchase], from pending payments that completed later,
     * and from unfinished purchases picked up by [restorePurchases].
     */
    public val purchaseEvents: Flow<PurchaseEvent>

    /** Ids of non-consumables and active subscriptions the user owns (verified). */
    public val ownedProducts: StateFlow<Set<String>>

    public suspend fun connect(): IapResult<Unit>

    /**
     * Store listing and prices. [productIds] null = every product in the [ProductCatalog].
     * Ids Play does not return are listed in [ProductQuery.unavailable], not reported as an error.
     */
    public suspend fun queryProducts(productIds: List<String>? = null): IapResult<ProductQuery>

    /**
     * Opens Play's purchase sheet. Success only means the sheet was shown: the purchase
     * outcome arrives on [purchaseEvents].
     */
    public suspend fun launchPurchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions = PurchaseOptions(),
    ): IapResult<Unit>

    /**
     * Finishes purchases that were paid but never granted / consumed / acknowledged (app killed
     * mid-flow, pending payment completed) and refreshes [ownedProducts]. Call on app start and
     * on resume; safe to call repeatedly.
     */
    public suspend fun restorePurchases(): IapResult<List<IapPurchase>>

    /** Releases the Play connection. The client cannot be used afterwards. */
    public fun close()
}

public enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CLOSED }

/** Receives the library's diagnostic logs. Default: no logging. */
public fun interface IapLogger {
    public fun log(level: Level, message: String, throwable: Throwable?)

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    public companion object {
        public val NONE: IapLogger = IapLogger { _, _, _ -> }
    }
}
