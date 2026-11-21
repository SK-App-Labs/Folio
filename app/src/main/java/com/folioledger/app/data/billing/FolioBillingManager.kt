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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private val _billingState = MutableStateFlow(TipJarBillingState(isConnecting = true))
    val billingState: StateFlow<TipJarBillingState> = _billingState.asStateFlow()

    private val cachedProductDetails = ConcurrentHashMap<String, ProductDetails>()

    @Volatile
    private var ownedPlaySkus: Set<String> = emptySet()

    @Volatile
    private var isServiceConnected = false
    private val isConnectingInFlight = AtomicBoolean(false)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _billingState.update { it.copy(isPurchaseInProgress = false, errorMessage = null) }
                if (purchases != null) {
                    val hasPending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
                    externalScope.launch {
                        var anyConfirmed = false
                        for (purchase in purchases) {
                            if (handlePurchase(purchase, clearPending = !hasPending)) {
                                anyConfirmed = true
                            }
                        }
                        if (anyConfirmed) {
                            _billingState.update { it.copy(hasVerifiedPlayPurchases = true) }
                            updateProductListState()
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

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    init {
        startConnection()
    }

    fun startConnection(onReady: (() -> Unit)? = null) {
        if (isServiceConnected && billingClient.isReady) {
            onReady?.invoke()
            return
        }

        if (!isConnectingInFlight.compareAndSet(false, true)) {
            return
        }

        _billingState.update { it.copy(isConnecting = true) }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnectingInFlight.set(false)
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isServiceConnected = true
                    _billingState.update {
                        it.copy(
                            isConnecting = false,
                            isStoreAvailable = true,
                            errorMessage = null
                        )
                    }
                    queryProductsAndPurchases()
                    onReady?.invoke()
                } else {
                    isServiceConnected = false
                    _billingState.update {
                        it.copy(
                            isConnecting = false,
                            isStoreAvailable = false,
                            errorMessage = "Google Play Store is unavailable on this device."
                        )
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnectingInFlight.set(false)
                isServiceConnected = false
                _billingState.update {
                    it.copy(
                        isConnecting = false,
                        isLoadingProducts = false,
                        isRestoringPurchases = false
                    )
                }
            }
        })
    }

    fun refresh() {
        if (!billingClient.isReady) {
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
        if (!billingClient.isReady) {
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

            val productDetailsResult = billingClient.queryProductDetails(params)
            if (productDetailsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = productDetailsResult.productDetailsList ?: emptyList()
                for (details in detailsList) {
                    cachedProductDetails[details.productId] = details
                }
                _billingState.update { it.copy(errorMessage = null) }
                updateProductListState()
            } else {
                _billingState.update {
                    it.copy(errorMessage = "Could not load tip prices from Google Play.")
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
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val purchasesResult = billingClient.queryPurchasesAsync(params)
        if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
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
            ownedPlaySkus = confirmedSkus
            _billingState.update { it.copy(hasVerifiedPlayPurchases = true) }
            updateProductListState()
        } else {
            _billingState.update {
                it.copy(errorMessage = "Could not check purchases with Google Play.")
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
                    val result = billingClient.acknowledgePurchase(acknowledgeParams)
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
                    ownedPlaySkus = ownedPlaySkus + purchase.products
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
            userPreferencesDataSource.updateUserPreferences { current ->
                current.copy(
                    isSupporter = true,
                    supporterTier = newTier.skuId
                )
            }
        }

        _billingState.update {
            it.copy(
                pendingMessage = if (clearPending) null else it.pendingMessage,
                errorMessage = null
            )
        }

        updateProductListState()
    }

    fun launchBillingFlow(activity: Activity, skuId: String): Boolean {
        if (!billingClient.isReady) {
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
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
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

            if (!billingClient.isReady) {
                startConnection()
                _billingState.update {
                    it.copy(
                        isRestoringPurchases = false,
                        errorMessage = "Connecting to Google Play. Please try again in a moment."
                    )
                }
                return@launch
            }

            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            val purchasesResult = billingClient.queryPurchasesAsync(params)
            if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
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
                ownedPlaySkus = confirmedSkus
                updateProductListState()

                _billingState.update {
                    it.copy(
                        isRestoringPurchases = false,
                        hasVerifiedPlayPurchases = true,
                        restoreMessage = if (confirmedSkus.isNotEmpty()) {
                            "Supporter purchases restored successfully!"
                        } else {
                            "No previous supporter purchases found on this Google account."
                        }
                    )
                }
            } else {
                _billingState.update {
                    it.copy(
                        isRestoringPurchases = false,
                        errorMessage = "Could not check purchases with Google Play (${purchasesResult.billingResult.debugMessage.ifBlank { "Code ${purchasesResult.billingResult.responseCode}" }})."
                    )
                }
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
        if (isServiceConnected) {
            billingClient.endConnection()
            isServiceConnected = false
        }
    }

    companion object {
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
