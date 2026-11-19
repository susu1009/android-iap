package com.banv.iap.testing

import com.banv.iap.IapPurchase
import com.banv.iap.PurchaseProcessor
import com.banv.iap.Verification

/**
 * Accepts every purchase without a server check, then calls [onGrant].
 * For tests and internal builds ONLY: shipping it lets anyone fake purchases.
 */
public class TrustAllProcessor(
    private val onGrant: suspend (IapPurchase) -> Boolean = { true },
) : PurchaseProcessor {
    override suspend fun verify(purchase: IapPurchase): Verification = Verification.Valid
    override suspend fun grant(purchase: IapPurchase): Boolean = onGrant(purchase)
}
