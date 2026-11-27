/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/billing/FolioBillingManager.kt
package com.folioledger.app.data.billing

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Immutable
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.folioledger.app.data.repository.UserPreferencesDataSource
import com.folioledger.app.domain.model.SupporterTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
data class TipProductUi(
    val tier: SupporterTier,
    val formattedPrice: String? = null,
    val isOwned: Boolean = false
)

@Immutable
data class TipJarBillingState(
    val isConnecting: Boolean = false,
    val isLoadingProducts: Boolean = false,
    val isRestoringPurchases: Boolean = false,
    val hasVerifiedPlayPurchases: Boolean = false,
    val isStoreAvailable: Boolean = true,
    val isOfflineError: Boolean = false,
    val products: List<TipProductUi> = emptyList(),
    val isPurchaseInProgress: Boolean = false,
    val pendingMessage: String? = null,
    val errorMessage: String? = null,
    val restoreMessage: String? = null
)

class FolioBillingManager(
    context: Context,
    private val userPreferencesDataSource: UserPreferencesDataSource,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private data class PendingConnectionCallback(
        val onReady: () -> Unit,
        val onUnavailable: ((String) -> Unit)? = null
    )

    private val appContext: Context = context.applicationContext
    private val _billingState = MutableStateFlow(TipJarBillingState(isConnecting = false))
    val billingState: StateFlow<TipJarBillingState> = _billingState.asStateFlow()

    private val cachedProductDetails = ConcurrentHashMap<String, ProductDetails>()

    private val ownedSkusLock = Any()
    @Volatile
    private var ownedPlaySkus: Set<String> = emptySet()

    @Volatile
    private var isServiceConnected = false
    private val isConnectingInFlight = AtomicBoolean(false)
    private val pendingReadyCallbacks = java.util.concurrent.ConcurrentLinkedQueue<PendingConnectionCallback>()
    private val billingClientLock = Any()
    @Volatile
    private var billingClient: BillingClient? = null
    private val purchaseQueryMutex = Mutex()
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var reconnectAttempt = 0

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _billingState.update { it.copy(isPurchaseInProgress = false, errorMessage = null) }
                if (purchases != null) {
                    val hasPending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
                    externalScope.launch {
                        purchaseQueryMutex.withLock {
                            var anyConfirmed = false
                            for (purchase in purchases) {
                                if (handlePurchase(purchase, clearPending = !hasPending)) {
                                    anyConfirmed = true
                                }
                            }
                            if (anyConfirmed) {
                                updateProductListState()
                                _billingState.update { it.copy(hasVerifiedPlayPurchases = true) }
                            }
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.update { it.copy(isPurchaseInProgress = false, errorMessage = null) }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _billingState.update {
                    it.copy(
                        isPurchaseInProgress = false,
                        errorMessage = "This tip tier is already unlocked on your account."
                    )
                }
                queryPurchases()
            }
            else -> {
                _billingState.update {
                    it.copy(
                        isPurchaseInProgress = false,
                        errorMessage = "Purchase could not be completed (${billingResult.debugMessage.ifBlank { "Code ${billingResult.responseCode}" }})."
                    )
                }
            }
        }
    }

    private fun getOrCreateBillingClient(): BillingClient {
        billingClient?.let { return it }
        return synchronized(billingClientLock) {
            billingClient ?: BillingClient.newBuilder(appContext)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build()
                )
                .enableAutoServiceReconnection()
                .build()
                .also { billingClient = it }
        }
    }

    fun startConnection(
        onReady: (() -> Unit)? = null,
        onUnavailable: ((String) -> Unit)? = null
    ) {
        if (onReady != null || onUnavailable != null) {
            pendingReadyCallbacks.offer(PendingConnectionCallback(onReady = onReady ?: {}, onUnavailable = onUnavailable))
        }

        val client = getOrCreateBillingClient()
        if (isServiceConnected && client.isReady) {
            while (pendingReadyCallbacks.isNotEmpty()) {
                pendingReadyCallbacks.poll()?.onReady?.invoke()
            }
            return
        }

        if (!isConnectingInFlight.compareAndSet(false, true)) {
            return
        }

        _billingState.update { it.copy(isConnecting = true) }

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingClient !== client) return
                isConnectingInFlight.set(false)
                val code = billingResult.responseCode
                if (code == BillingClient.BillingResponseCode.OK) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectAttempt = 0
                    isServiceConnected = true
                    _billingState.update {
                        it.copy(
                            isConnecting = false,
                            isStoreAvailable = true,
                            isOfflineError = false,
                            errorMessage = null
                        )
                    }
                    val hasReadyCallbacks = pendingReadyCallbacks.isNotEmpty()
                    if (hasReadyCallbacks) {
                        queryProducts()
                    } else {
                        queryProductsAndPurchases()
                    }
                    while (pendingReadyCallbacks.isNotEmpty()) {
                        pendingReadyCallbacks.poll()?.onReady?.invoke()
                    }
                } else {
                    isServiceConnected = false
                    val isPermanentUnavailable = code == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ||
                        code == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
                    val msg = if (isPermanentUnavailable) {
                        "Google Play Store is unavailable on this device."
                    } else {
                        "You're offline. Connect to the internet to load Google Play tip options."
                    }
                    while (pendingReadyCallbacks.isNotEmpty()) {
                        pendingReadyCallbacks.poll()?.onUnavailable?.invoke(msg)
                    }
                    _billingState.update {
                        it.copy(
                            isConnecting = false,
                            isStoreAvailable = !isPermanentUnavailable,
                            isOfflineError = !isPermanentUnavailable,
                            errorMessage = msg
                        )
                    }
                    if (!isPermanentUnavailable) {
                        scheduleReconnectIfNeeded()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                if (billingClient !== client) return
                isConnectingInFlight.set(false)
                isServiceConnected = false
                _billingState.update {
                    it.copy(
                        isConnecting = false,
                        isLoadingProducts = false,
                        isPurchaseInProgress = false,
                        isRestoringPurchases = false
                    )
                }
            }
        })
    }

    private fun scheduleReconnectIfNeeded() {
        val client = billingClient
        if (isServiceConnected || client?.isReady == true || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return
        if (reconnectJob?.isActive == true) return
        val attemptIndex = reconnectAttempt++
        val delayMs = (1_000L shl attemptIndex).coerceAtMost(4_000L)
        reconnectJob = externalScope.launch {
            delay(delayMs)
            if (!isServiceConnected && billingClient?.isReady != true) {
                startConnection()
            }
        }
    }

    fun refresh() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _billingState.update { it.copy(isPurchaseInProgress = false) }
        if (billingClient?.isReady != true) {
            startConnection()
        } else {
            queryProductsAndPurchases()
        }
    }

    fun queryProductsAndPurchases() {
        externalScope.launch {
            queryProductsInternal()
            queryPurchasesInternal()
        }
    }

    fun queryProducts() {
        externalScope.launch {
            queryProductsInternal()
        }
    }

    private suspend fun queryProductsInternal() {
        val client = getOrCreateBillingClient()
        if (!client.isReady) {
            startConnection()
            return
        }

        _billingState.update { it.copy(isLoadingProducts = true) }
        try {
            val productList = SupporterTier.entries.map { tier ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(tier.skuId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            val productDetailsResult = client.queryProductDetails(params)
            val responseCode = productDetailsResult.billingResult.responseCode
            if (responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = productDetailsResult.productDetailsList ?: emptyList()
                for (details in detailsList) {
                    cachedProductDetails[details.productId] = details
                }
                _billingState.update { it.copy(isOfflineError = false, errorMessage = null) }
                updateProductListState()
            } else {
                val offline = isTransientOfflineCode(responseCode)
                _billingState.update {
                    it.copy(
                        isOfflineError = offline,
                        errorMessage = if (offline) {
                            "You're offline. Connect to the internet to load Google Play tip prices."
                        } else {
                            "Could not load tip prices from Google Play."
                        }
                    )
                }
            }
        } finally {
            _billingState.update { it.copy(isLoadingProducts = false) }
        }
    }

    fun queryPurchases() {
        externalScope.launch {
            queryPurchasesInternal()
        }
    }

    private suspend fun queryPurchasesInternal() {
        val client = getOrCreateBillingClient()
        if (!client.isReady) {
            startConnection()
            return
        }
        syncPurchasesWithPlayLocked(client, includeRestoreMessage = false)
    }

    private suspend fun syncPurchasesWithPlayLocked(
        client: BillingClient,
        includeRestoreMessage: Boolean
    ) {
        purchaseQueryMutex.withLock {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            val purchasesResult = client.queryPurchasesAsync(params)
            val code = purchasesResult.billingResult.responseCode
            if (code == BillingClient.BillingResponseCode.OK) {
                val purchases = purchasesResult.purchasesList
                val hasPending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
                if (!hasPending) {
                    _billingState.update { it.copy(pendingMessage = null) }
                }

                val confirmedSkus = mutableSetOf<String>()
                for (purchase in purchases) {
                    if (handlePurchase(purchase, clearPending = false)) {
                        confirmedSkus.addAll(purchase.products)
                    }
                }
                synchronized(ownedSkusLock) {
                    ownedPlaySkus = confirmedSkus
                }
                updateProductListState()
                _billingState.update {
                    it.copy(
                        hasVerifiedPlayPurchases = true,
                        isOfflineError = false,
                        restoreMessage = if (includeRestoreMessage) {
                            if (confirmedSkus.isNotEmpty()) {
                                "Supporter purchases restored."
                            } else {
                                "No previous supporter purchases found on this Google account."
                            }
                        } else {
                            it.restoreMessage
                        }
                    )
                }
            } else {
                val offline = isTransientOfflineCode(code)
                _billingState.update {
                    it.copy(
                        isOfflineError = offline,
                        errorMessage = if (offline) {
                            if (includeRestoreMessage) {
                                "You're offline. Connect to the internet to restore Google Play purchases."
                            } else {
                                "You're offline. Connect to the internet to check Google Play purchases."
                            }
                        } else if (includeRestoreMessage) {
                            "Could not check purchases with Google Play (${purchasesResult.billingResult.debugMessage.ifBlank { "Code $code" }})."
                        } else {
                            "Could not check purchases with Google Play."
                        }
                    )
                }
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase, clearPending: Boolean): Boolean {
        return when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                val highestTier = resolveHighestTier(purchase.products) ?: return false

                val isConfirmed = if (!purchase.isAcknowledged) {
                    val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    val result = getOrCreateBillingClient().acknowledgePurchase(acknowledgeParams)
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        true
                    } else {
                        _billingState.update {
                            it.copy(errorMessage = "Could not confirm purchase with Google Play. It will be retried automatically.")
                        }
                        false
                    }
                } else {
                    true
                }

                if (isConfirmed) {
                    synchronized(ownedSkusLock) {
                        ownedPlaySkus = ownedPlaySkus + purchase.products
                    }
                    onTierVerified(highestTier, clearPending = clearPending)
                }
                isConfirmed
            }
            Purchase.PurchaseState.PENDING -> {
                _billingState.update {
                    it.copy(
                        pendingMessage = "Payment is pending confirmation from Google Play. Your badge will unlock automatically once confirmed."
                    )
                }
                false
            }
            else -> false
        }
    }

    private suspend fun onTierVerified(newTier: SupporterTier, clearPending: Boolean) {
        val currentPrefs = userPreferencesDataSource.observeUserPreferences().first()
        val currentTier = SupporterTier.fromSkuOrTitle(currentPrefs.supporterTier)
            ?: if (currentPrefs.isSupporter) SupporterTier.GENEROUS_SUPPORTER else null

        if (shouldPersistTierUpgrade(currentTier, newTier)) {
            val persistResult = userPreferencesDataSource.updateUserPreferences { current ->
                current.copy(
                    isSupporter = true,
                    supporterTier = newTier.skuId
                )
            }
            if (persistResult.isFailure) {
                _billingState.update {
                    it.copy(
                        errorMessage = persistResult.exceptionOrNull()?.message
                            ?: "Could not save your supporter badge locally. Please try restoring purchases."
                    )
                }
                return
            }
        }

        _billingState.update {
            it.copy(
                pendingMessage = if (clearPending) null else it.pendingMessage,
                isOfflineError = false,
                errorMessage = null
            )
        }

        updateProductListState()
    }

    fun launchBillingFlow(activity: Activity, skuId: String): Boolean {
        val client = getOrCreateBillingClient()
        if (!client.isReady) {
            _billingState.update {
                it.copy(errorMessage = "Connecting to Google Play. Please try again in a moment.")
            }
            startConnection()
            return false
        }

        val productDetails = cachedProductDetails[skuId]
        if (productDetails == null) {
            _billingState.update {
                it.copy(errorMessage = "Product details not loaded yet. Retrying...")
            }
            queryProducts()
            return false
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        _billingState.update { it.copy(isPurchaseInProgress = true, errorMessage = null, restoreMessage = null) }
        val result = client.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingState.update {
                it.copy(
                    isPurchaseInProgress = false,
                    errorMessage = "Failed to launch Google Play payment (${result.debugMessage.ifBlank { "Code ${result.responseCode}" }})."
                )
            }
            return false
        }
        return true
    }

    fun restorePurchases() {
        externalScope.launch {
            _billingState.update {
                it.copy(isRestoringPurchases = true, restoreMessage = null, errorMessage = null)
            }

            val client = getOrCreateBillingClient()
            if (!client.isReady) {
                startConnection(
                    onReady = { restorePurchases() },
                    onUnavailable = { message ->
                        _billingState.update {
                            it.copy(isRestoringPurchases = false, errorMessage = message)
                        }
                    }
                )
                return@launch
            }

            try {
                syncPurchasesWithPlayLocked(client, includeRestoreMessage = true)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _billingState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Failed to check Google Play purchases.")
                }
            } finally {
                _billingState.update { it.copy(isRestoringPurchases = false) }
            }
        }
    }

    private fun updateProductListState() {
        val cachedPrices = SupporterTier.entries.associate { tier ->
            tier.skuId to cachedProductDetails[tier.skuId]?.oneTimePurchaseOfferDetails?.formattedPrice
        }
        val productsUi = buildTipProducts(
            cachedPricesBySku = cachedPrices,
            ownedPlaySkus = ownedPlaySkus
        )
        _billingState.update {
            it.copy(products = productsUi)
        }
    }

    fun destroy() {
        reconnectJob?.cancel()
        reconnectJob = null
        isConnectingInFlight.set(false)
        pendingReadyCallbacks.clear()
        synchronized(billingClientLock) {
            runCatching {
                billingClient?.endConnection()
            }
            billingClient = null
        }
        isServiceConnected = false
        _billingState.update {
            it.copy(
                isConnecting = false,
                isLoadingProducts = false,
                isPurchaseInProgress = false,
                isRestoringPurchases = false
            )
        }
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 3

        @Suppress("DEPRECATION")
        internal fun isTransientOfflineCode(responseCode: Int): Boolean =
            responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                responseCode == BillingClient.BillingResponseCode.NETWORK_ERROR ||
                responseCode == BillingClient.BillingResponseCode.SERVICE_TIMEOUT ||
                responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED

        fun resolveHighestTier(productIds: List<String>): SupporterTier? {
            return productIds.mapNotNull { SupporterTier.fromSkuOrTitle(it) }
                .maxByOrNull { it.ordinal }
        }

        fun shouldPersistTierUpgrade(current: SupporterTier?, incoming: SupporterTier): Boolean {
            val currentOrdinal = current?.ordinal ?: -1
            return incoming.ordinal >= currentOrdinal
        }

        fun buildTipProducts(
            cachedPricesBySku: Map<String, String?>,
            ownedPlaySkus: Set<String>
        ): List<TipProductUi> {
            return SupporterTier.entries.map { tier ->
                TipProductUi(
                    tier = tier,
                    formattedPrice = cachedPricesBySku[tier.skuId],
                    isOwned = ownedPlaySkus.contains(tier.skuId)
                )
            }
        }
    }
}
