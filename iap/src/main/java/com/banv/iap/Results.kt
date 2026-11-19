package com.banv.iap

/** Outcome of a purchase flow, delivered on [IapClient.purchaseEvents]. */
public sealed interface PurchaseEvent {
    /** Verified, granted and consumed / acknowledged. */
    public data class Completed(val purchase: IapPurchase) : PurchaseEvent

    /** Paid with a delayed method (cash, bank transfer). Completed later via restore. */
    public data class Pending(val purchase: IapPurchase) : PurchaseEvent

    public data object Cancelled : PurchaseEvent

    public data class Failed(val error: IapError, val purchase: IapPurchase? = null) : PurchaseEvent
}

public sealed interface IapResult<out T> {
    public data class Success<T>(val value: T) : IapResult<T>
    public data class Failure(val error: IapError) : IapResult<Nothing>
}

public inline fun <T> IapResult<T>.getOrNull(): T? = (this as? IapResult.Success)?.value

public inline fun <T> IapResult<T>.onSuccess(block: (T) -> Unit): IapResult<T> =
    also { if (it is IapResult.Success) block(it.value) }

public inline fun <T> IapResult<T>.onFailure(block: (IapError) -> Unit): IapResult<T> =
    also { if (it is IapResult.Failure) block(it.error) }

/** Why an operation failed. Carries no user-facing text: map it to your own strings. */
public sealed interface IapError {
    /** True when trying again later may succeed. */
    public val isRetryable: Boolean get() = false

    public data object UserCancelled : IapError

    /** Already owned. The library restores unfinished purchases before reporting this. */
    public data object ItemAlreadyOwned : IapError

    public data object ItemUnavailable : IapError

    /** No Play Store, unsupported country, or billing not supported on this device / account. */
    public data object BillingUnavailable : IapError

    /** Play service disconnected, timed out or no network. */
    public data object ServiceUnavailable : IapError {
        override val isRetryable: Boolean get() = true
    }

    /** The product id is not in the app's [ProductCatalog]. */
    public data object ProductNotInCatalog : IapError

    public data object VerificationFailed : IapError

    /** [PurchaseProcessor.grant] returned false or threw. Retried on the next restore. */
    public data object GrantFailed : IapError {
        override val isRetryable: Boolean get() = true
    }

    /** Wrong integration (bad offer token, product type mismatch…). Fix in code or Play Console. */
    public data class DeveloperError(val message: String) : IapError

    public data class Unknown(val code: Int, val message: String) : IapError
}
