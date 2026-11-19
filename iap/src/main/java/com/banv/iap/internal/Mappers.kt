package com.banv.iap.internal

import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.banv.iap.IapProduct
import com.banv.iap.IapPurchase
import com.banv.iap.OneTimeOffer
import com.banv.iap.PricingPhase
import com.banv.iap.ProductType
import com.banv.iap.PurchaseState
import com.banv.iap.Recurrence
import com.banv.iap.SubscriptionOffer

internal fun ProductDetails.toIapProduct(type: ProductType): IapProduct = IapProduct(
    productId = productId,
    type = type,
    title = name,
    description = description,
    oneTimeOffers = oneTimeOffers().map {
        OneTimeOffer(
            offerId = it.offerId,
            offerToken = it.offerToken,
            formattedPrice = it.formattedPrice,
            priceMicros = it.priceAmountMicros,
            currencyCode = it.priceCurrencyCode,
        )
    },
    subscriptionOffers = subscriptionOfferDetails.orEmpty().map { offer ->
        SubscriptionOffer(
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerToken = offer.offerToken,
            tags = offer.offerTags,
            pricingPhases = offer.pricingPhases.pricingPhaseList.map { phase ->
                PricingPhase(
                    formattedPrice = phase.formattedPrice,
                    priceMicros = phase.priceAmountMicros,
                    currencyCode = phase.priceCurrencyCode,
                    billingPeriod = phase.billingPeriod,
                    billingCycleCount = phase.billingCycleCount,
                    recurrence = when (phase.recurrenceMode) {
                        ProductDetails.RecurrenceMode.INFINITE_RECURRING -> Recurrence.INFINITE
                        ProductDetails.RecurrenceMode.FINITE_RECURRING -> Recurrence.FINITE
                        else -> Recurrence.NON_RECURRING
                    },
                )
            },
        )
    },
)

/** One-time offers: the multi-offer list (Billing 8+), else the legacy single offer. */
private fun ProductDetails.oneTimeOffers(): List<ProductDetails.OneTimePurchaseOfferDetails> =
    oneTimePurchaseOfferDetailsList?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(oneTimePurchaseOfferDetails)

/**
 * Offer used when the app passes no offerToken: a subscription's first base plan (no promo
 * offer), or the first one-time offer. Null for a legacy one-time product (Play needs none).
 */
internal fun ProductDetails.defaultOfferToken(): String? {
    val subs = subscriptionOfferDetails.orEmpty()
    if (subs.isNotEmpty()) return (subs.firstOrNull { it.offerId == null } ?: subs.first()).offerToken
    return oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
}

/** Null for UNSPECIFIED_STATE purchases, which must not be granted. */
internal fun Purchase.toIapPurchase(): IapPurchase? {
    val state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
        else -> return null
    }
    if (products.isEmpty()) return null
    return IapPurchase(
        productIds = products,
        purchaseToken = purchaseToken,
        orderId = orderId,
        state = state,
        purchaseTimeMillis = purchaseTime,
        quantity = quantity,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId,
        obfuscatedProfileId = accountIdentifiers?.obfuscatedProfileId,
        originalJson = originalJson,
        signature = signature,
    )
}
