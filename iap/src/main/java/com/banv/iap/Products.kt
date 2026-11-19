package com.banv.iap

/**
 * How a product behaves after purchase. Play reports consumables and non-consumables the same
 * way (both are in-app products), so the app must declare which is which.
 */
public enum class ProductType {
    /** Bought repeatedly (Gem packs, coins). Consumed after [PurchaseProcessor.grant]. */
    CONSUMABLE,

    /** Bought once and kept (remove ads, lifetime unlock). Acknowledged after grant. */
    NON_CONSUMABLE,

    /** Recurring plan. Acknowledged after grant. */
    SUBSCRIPTION,
}

/** One product the app sells, as configured in Play Console. */
public data class ProductSpec(
    val productId: String,
    val type: ProductType,
)

/**
 * Source of the products the app sells. Called whenever the library needs the catalogue,
 * so it may read remote config; keep it cheap or cache inside.
 */
public fun interface ProductCatalog {
    public suspend fun products(): List<ProductSpec>
}

/** Result of [IapClient.queryProducts]. */
public data class ProductQuery(
    val products: List<IapProduct>,
    /** Requested ids Play did not return (not active in Play Console, wrong id, region). */
    val unavailable: List<String>,
)

/** A product with its store listing and prices, as Play returned it. */
public data class IapProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val description: String,
    /** Empty for subscriptions. */
    val oneTimeOffers: List<OneTimeOffer>,
    /** Empty for one-time products. */
    val subscriptionOffers: List<SubscriptionOffer>,
) {
    /**
     * Price to show in a list: the first one-time offer, or the recurring (last) phase of the
     * first subscription base plan. Null when Play returned no offer.
     */
    val displayPrice: String?
        get() = oneTimeOffers.firstOrNull()?.formattedPrice
            ?: subscriptionOffers.firstOrNull { it.offerId == null }?.pricingPhases?.lastOrNull()?.formattedPrice
            ?: subscriptionOffers.firstOrNull()?.pricingPhases?.lastOrNull()?.formattedPrice
}

public data class OneTimeOffer(
    /** Null for the product's default purchase option. */
    val offerId: String?,
    val offerToken: String,
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
)

public data class SubscriptionOffer(
    val basePlanId: String,
    /** Null for the base plan itself; set for promotional offers on top of it. */
    val offerId: String?,
    val offerToken: String,
    val tags: List<String>,
    val pricingPhases: List<PricingPhase>,
)

public data class PricingPhase(
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    /** ISO-8601 duration, e.g. "P1W", "P1M", "P1Y". */
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrence: Recurrence,
)

public enum class Recurrence { INFINITE, FINITE, NON_RECURRING }
