package com.banv.iap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IapProductTest {
    private fun phase(price: String, recurrence: Recurrence = Recurrence.INFINITE) =
        PricingPhase(price, 0, "VND", "P1M", 0, recurrence)

    @Test
    fun displayPrice_oneTime_usesFirstOffer() {
        val p = product(oneTime = listOf(OneTimeOffer(null, "t", "25.000 ₫", 25_000_000_000, "VND")))
        assertEquals("25.000 ₫", p.displayPrice)
    }

    @Test
    fun displayPrice_subscription_prefersBasePlanRecurringPhase() {
        val trialOffer = SubscriptionOffer("monthly", "trial", "t1", emptyList(),
            listOf(phase("Miễn phí", Recurrence.FINITE), phase("99.000 ₫")))
        val basePlan = SubscriptionOffer("monthly", null, "t2", emptyList(), listOf(phase("99.000 ₫")))
        assertEquals("99.000 ₫", product(subs = listOf(trialOffer, basePlan)).displayPrice)
    }

    @Test
    fun displayPrice_noOffers_isNull() {
        assertNull(product().displayPrice)
    }

    private fun product(oneTime: List<OneTimeOffer> = emptyList(), subs: List<SubscriptionOffer> = emptyList()) =
        IapProduct("id", ProductType.CONSUMABLE, "t", "d", oneTime, subs)
}
