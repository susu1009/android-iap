package com.banv.iap

/**
 * Implemented by the app: decides whether a purchase is genuine and delivers what was bought.
 * The library consumes / acknowledges a purchase only after [grant] returned true, so a crash
 * in between never loses a paid purchase — it is re-delivered by [IapClient.restorePurchases].
 */
public interface PurchaseProcessor {
    /** Checks the purchase with your server (Google Play Developer API). Called before [grant]. */
    public suspend fun verify(purchase: IapPurchase): Verification

    /**
     * Delivers the purchase (credit Gems, unlock Premium…).
     *
     * MUST be idempotent by [IapPurchase.purchaseToken]: Play may deliver the same purchase
     * more than once. Return true when delivered (or already delivered before); false or a
     * thrown exception leaves the purchase unfinished, to be retried on the next restore.
     */
    public suspend fun grant(purchase: IapPurchase): Boolean
}

public sealed interface Verification {
    /** Genuine: go on to [PurchaseProcessor.grant]. */
    public data object Valid : Verification

    /** Fake or already used: nothing is granted, consumed or acknowledged. */
    public data object Invalid : Verification

    /** Server unreachable: leave the purchase as is and retry on the next restore. */
    public data object RetryLater : Verification
}
