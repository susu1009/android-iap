package com.banv.iap

import android.app.Activity
import android.content.Context
import com.banv.iap.internal.BillingGateway
import com.banv.iap.internal.PlayBillingGateway
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [IapClient] backed by Google Play Billing.
 *
 * Every PURCHASED purchase goes: [PurchaseProcessor.verify] → [PurchaseProcessor.grant] →
 * consume (CONSUMABLE) or acknowledge (others) → [PurchaseEvent.Completed]. A purchase is
 * finished only after the app granted it, so a crash in between is repaired by the next
 * [restorePurchases].
 */
public class GooglePlayIapClient internal constructor(
    private val gateway: BillingGateway,
    private val catalog: ProductCatalog,
    private val processor: PurchaseProcessor,
    private val logger: IapLogger,
    private val mainDispatcher: CoroutineDispatcher,
    backgroundDispatcher: CoroutineDispatcher,
) : IapClient {

    public class Builder(context: Context) {
        private val appContext = context.applicationContext
        private var catalog: ProductCatalog? = null
        private var processor: PurchaseProcessor? = null
        private var logger: IapLogger = IapLogger.NONE

        /** Required. The products this app sells. */
        public fun catalog(catalog: ProductCatalog): Builder = apply { this.catalog = catalog }

        /** Required. Verifies and delivers purchases. */
        public fun processor(processor: PurchaseProcessor): Builder = apply { this.processor = processor }

        public fun logger(logger: IapLogger): Builder = apply { this.logger = logger }

        public fun build(): GooglePlayIapClient = GooglePlayIapClient(
            gateway = PlayBillingGateway(appContext, logger),
            catalog = requireNotNull(catalog) { "GooglePlayIapClient.Builder: catalog(...) is required" },
            processor = requireNotNull(processor) { "GooglePlayIapClient.Builder: processor(...) is required" },
            logger = logger,
            mainDispatcher = Dispatchers.Main,
            backgroundDispatcher = Dispatchers.Default,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

    private val events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 32)
    override val purchaseEvents: Flow<PurchaseEvent> = events.asSharedFlow()

    private val owned = MutableStateFlow<Set<String>>(emptySet())
    override val ownedProducts: StateFlow<Set<String>> = owned.asStateFlow()

    private val connectMutex = Mutex()

    /** One purchase handled at a time: Play's callback and a restore may race on the same token. */
    private val handleMutex = Mutex()

    /** Tokens already reported as Pending, so each restore does not repeat the event. */
    private val reportedPending = mutableSetOf<String>()

    init {
        gateway.purchasesListener = { update -> scope.launch { onPurchasesUpdated(update) } }
    }

    // ---- connection ---------------------------------------------------------------------

    override suspend fun connect(): IapResult<Unit> = connectMutex.withLock {
        if (state.value == ConnectionState.CLOSED) return IapResult.Failure(IapError.ServiceUnavailable)
        if (gateway.isReady) {
            state.value = ConnectionState.CONNECTED
            return IapResult.Success(Unit)
        }
        state.value = ConnectionState.CONNECTING
        var last: IapResult<Unit> = IapResult.Failure(IapError.ServiceUnavailable)
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
            last = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { gateway.connect() }
                ?: IapResult.Failure(IapError.ServiceUnavailable)
            val error = (last as? IapResult.Failure)?.error ?: break
            if (!error.isRetryable) break
            logger.log(IapLogger.Level.WARN, "connect attempt ${attempt + 1} failed: $error", null)
        }
        state.value = if (last is IapResult.Success) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        last
    }

    private suspend inline fun <T> connected(block: () -> IapResult<T>): IapResult<T> {
        if (!gateway.isReady) {
            val result = connect()
            if (result is IapResult.Failure) return result
        }
        return block()
    }

    // ---- products -----------------------------------------------------------------------

    override suspend fun queryProducts(productIds: List<String>?): IapResult<ProductQuery> = connected {
        val specs = catalog.products()
        val wanted = productIds?.toSet()
        val selected = if (wanted == null) specs else specs.filter { it.productId in wanted }
        val notInCatalog = wanted.orEmpty() - specs.map { it.productId }.toSet()
        if (selected.isEmpty()) return@connected IapResult.Success(ProductQuery(emptyList(), notInCatalog.toList()))
        when (val result = gateway.queryProducts(selected)) {
            is IapResult.Success -> IapResult.Success(
                result.value.copy(unavailable = result.value.unavailable + notInCatalog),
            )
            is IapResult.Failure -> result
        }
    }

    // ---- purchase -----------------------------------------------------------------------

    override suspend fun launchPurchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions,
    ): IapResult<Unit> = connected {
        val spec = catalog.products().firstOrNull { it.productId == productId }
            ?: return@connected IapResult.Failure(IapError.ProductNotInCatalog)
        if (!gateway.hasDetails(productId)) {
            val query = gateway.queryProducts(listOf(spec))
            if (query is IapResult.Failure) return@connected query
            if (!gateway.hasDetails(productId)) return@connected IapResult.Failure(IapError.ItemUnavailable)
        }
        val result = withContext(mainDispatcher) { gateway.launch(activity, spec, options) }
        if ((result as? IapResult.Failure)?.error == IapError.ItemAlreadyOwned) {
            // Usually a consumable whose earlier purchase was never consumed: finish it now.
            restorePurchases()
        }
        result
    }

    private suspend fun onPurchasesUpdated(update: IapResult<List<IapPurchase>>) {
        when (update) {
            is IapResult.Success -> update.value.forEach { handle(it, fromRestore = false) }
            is IapResult.Failure -> when (update.error) {
                IapError.UserCancelled -> events.emit(PurchaseEvent.Cancelled)
                IapError.ItemAlreadyOwned -> {
                    restorePurchases()
                    events.emit(PurchaseEvent.Failed(IapError.ItemAlreadyOwned))
                }
                else -> events.emit(PurchaseEvent.Failed(update.error))
            }
        }
    }

    // ---- restore ------------------------------------------------------------------------

    override suspend fun restorePurchases(): IapResult<List<IapPurchase>> = connected {
        when (val result = gateway.queryPurchases()) {
            is IapResult.Failure -> result
            is IapResult.Success -> {
                val finished = result.value.mapNotNull { handle(it, fromRestore = true) }
                val types = catalog.products().associate { it.productId to it.type }
                // Rebuilt from scratch so expired subscriptions and refunds drop out. `finished`
                // holds only purchases that are granted (now or earlier), plus pending ones.
                owned.value = finished
                    .filter { it.state == PurchaseState.PURCHASED }
                    .flatMap { it.productIds }
                    .filter { types[it] != null && types[it] != ProductType.CONSUMABLE }
                    .toSet()
                IapResult.Success(result.value)
            }
        }
    }

    // ---- purchase processing ------------------------------------------------------------

    /**
     * Runs one purchase through verify → grant → finish. Returns the purchase as it now stands
     * (acknowledged when finished), or null when it was rejected or left for a later retry.
     * Failures found during a restore are only logged: the user is not in a purchase flow.
     */
    private suspend fun handle(purchase: IapPurchase, fromRestore: Boolean): IapPurchase? = handleMutex.withLock {
        val token = purchase.purchaseToken
        if (purchase.state == PurchaseState.PENDING) {
            if (reportedPending.add(token)) events.emit(PurchaseEvent.Pending(purchase))
            return@withLock purchase
        }
        reportedPending.remove(token)

        val type = catalog.products().firstOrNull { it.productId == purchase.productId }?.type
        if (type == null) {
            logger.log(IapLogger.Level.WARN, "Purchase of ${purchase.productId} not in catalog: left unfinished", null)
            return@withLock null
        }
        // Non-consumables and subscriptions already acknowledged were granted before.
        if (type != ProductType.CONSUMABLE && purchase.isAcknowledged) return@withLock purchase

        suspend fun fail(error: IapError, message: String, t: Throwable? = null): IapPurchase? {
            logger.log(IapLogger.Level.WARN, "$message (${purchase.productId}, order ${purchase.orderId})", t)
            if (!fromRestore) events.emit(PurchaseEvent.Failed(error, purchase))
            return null
        }

        val verification = try {
            processor.verify(purchase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(IapLogger.Level.WARN, "verify threw", e)
            Verification.RetryLater
        }
        when (verification) {
            Verification.Valid -> Unit
            Verification.Invalid -> return@withLock fail(IapError.VerificationFailed, "Verification rejected")
            Verification.RetryLater -> return@withLock fail(IapError.ServiceUnavailable, "Verification unavailable, retry on restore")
        }

        val granted = try {
            processor.grant(purchase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock fail(IapError.GrantFailed, "grant threw", e)
        }
        if (!granted) return@withLock fail(IapError.GrantFailed, "grant returned false")

        // Granted: a failed consume/acknowledge is retried on the next restore (grant is idempotent).
        val finish = if (type == ProductType.CONSUMABLE) gateway.consume(token) else gateway.acknowledge(token)
        if (finish is IapResult.Failure) {
            logger.log(IapLogger.Level.WARN, "finish failed: ${finish.error}, retried on next restore", null)
        }
        val done = purchase.copy(isAcknowledged = finish is IapResult.Success || purchase.isAcknowledged)
        if (type != ProductType.CONSUMABLE) owned.update { it + purchase.productIds }
        events.emit(PurchaseEvent.Completed(done))
        done
    }

    override fun close() {
        state.value = ConnectionState.CLOSED
        gateway.purchasesListener = null
        gateway.close()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
