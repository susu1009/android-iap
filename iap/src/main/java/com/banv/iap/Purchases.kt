package com.banv.iap

public enum class PurchaseState { PURCHASED, PENDING }

/** A Play purchase. Send [originalJson] + [signature] (or [purchaseToken]) to your server to verify it. */
public data class IapPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val orderId: String?,
    val state: PurchaseState,
    val purchaseTimeMillis: Long,
    val quantity: Int,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean,
    val obfuscatedAccountId: String?,
    val obfuscatedProfileId: String?,
    val originalJson: String,
    val signature: String,
) {
    /** The first (usually only) product of this purchase. */
    val productId: String get() = productIds.first()
}

/** Options for [IapClient.launchPurchase]. */
public data class PurchaseOptions(
    /** A specific offer's token (subscription base plan / offer, or one-time offer). Null = default offer. */
    val offerToken: String? = null,
    /** A hash of your user id. Helps Play detect fraud and lets your server match the purchase to a user. */
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    /** Set to upgrade or downgrade an existing subscription. */
    val subscriptionUpdate: SubscriptionUpdate? = null,
)

public data class SubscriptionUpdate(
    val oldPurchaseToken: String,
    val replacementMode: ReplacementMode,
)

/** How Play switches an existing subscription to the new one. */
public enum class ReplacementMode {
    WITH_TIME_PRORATION,
    CHARGE_PRORATED_PRICE,
    CHARGE_FULL_PRICE,
    WITHOUT_PRORATION,
    DEFERRED,
}
