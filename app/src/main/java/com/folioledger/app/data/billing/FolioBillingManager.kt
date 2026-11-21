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

@Immutable
data class TipProductUi(
    val tier: SupporterTier,
    val formattedPrice: String,
    val isOwned: Boolean = false
)

@Immutable
data class TipJarBillingState(
    val isConnecting: Boolean = false,
    val isStoreAvailable: Boolean = true,
    val products: List<TipProductUi> = emptyList(),
    val isPurchaseInProgress: Boolean = false,
    val pendingMessage: String? = null,
    val errorMessage: String? = null
)

class FolioBillingManager(
    context: Context,
    private val userPreferencesDataSource: UserPreferencesDataSource,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val _billingState = MutableStateFlow(TipJarBillingState(isConnecting = true))
    val billingState: StateFlow<TipJarBillingState> = _billingState.asStateFlow()

    private val cachedProductDetails = ConcurrentHashMap<String, ProductDetails>()
    private var isServiceConnected = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _billingState.update { it.copy(isPurchaseInProgress = false, errorMessage = null) }
                if (purchases != null) {
                    externalScope.launch {
                        for (purchase in purchases) {
                            handlePurchase(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.update { it.copy(isPurchaseInProgress = false) }
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
        .build()

    init {
        startConnection()
    }

    fun startConnection(onReady: (() -> Unit)? = null) {
        if (isServiceConnected && billingClient.isReady) {
            onReady?.invoke()
            return
        }

        _billingState.update { it.copy(isConnecting = true) }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isServiceConnected = true
                    _billingState.update {
                        it.copy(
                            isConnecting = false,
                            isStoreAvailable = true,
                            errorMessage = null
                        )
                    }
                    queryProducts()
                    queryPurchases()
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
                isServiceConnected = false
                _billingState.update {
                    it.copy(isConnecting = false)
                }
            }
        })
    }

    fun queryProducts() {
        if (!billingClient.isReady) {
            startConnection { queryProducts() }
            return
        }

        val productList = SupporterTier.entries.map { tier ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(tier.skuId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (details in productDetailsList) {
                    cachedProductDetails[details.productId] = details
                }
                updateProductListState()
            } else {
                _billingState.update {
                    it.copy(errorMessage = "Could not load tip prices from Google Play.")
                }
            }
        }
    }

    fun queryPurchases() {
        if (!billingClient.isReady) {
            startConnection { queryPurchases() }
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                externalScope.launch {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                    updateProductListState()
                }
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                val highestTier = resolveHighestTier(purchase.products) ?: return

                if (!purchase.isAcknowledged) {
                    val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(acknowledgeParams) { acknowledgeResult ->
                        if (acknowledgeResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            externalScope.launch {
                                onTierVerified(highestTier)
                            }
                        } else {
                            _billingState.update {
                                it.copy(errorMessage = "Could not confirm purchase with Google Play. It will be retried automatically.")
                            }
                        }
                    }
                } else {
                    onTierVerified(highestTier)
                }
            }
            Purchase.PurchaseState.PENDING -> {
                _billingState.update {
                    it.copy(
                        pendingMessage = "Payment is pending confirmation from Google Play. Your badge will unlock automatically once confirmed."
                    )
                }
            }
        }
    }

    private suspend fun onTierVerified(newTier: SupporterTier) {
        val currentPrefs = userPreferencesDataSource.observeUserPreferences().first()
        val currentTier = SupporterTier.fromSkuOrTitle(currentPrefs.supporterTier)
            ?: if (currentPrefs.isSupporter) SupporterTier.GENEROUS_SUPPORTER else null
        val currentOrdinal = currentTier?.ordinal ?: -1

        // Upgrade or equal tier: persist badge without allowing downgrades
        if (newTier.ordinal >= currentOrdinal) {
            userPreferencesDataSource.updateUserPreferences { current ->
                current.copy(
                    isSupporter = true,
                    supporterTier = newTier.title
                )
            }
        }

        updateProductListState()
    }

    private fun resolveHighestTier(productIds: List<String>): SupporterTier? {
        return productIds.mapNotNull { SupporterTier.fromSkuOrTitle(it) }
            .maxByOrNull { it.ordinal }
    }

    fun launchBillingFlow(activity: Activity, skuId: String): Boolean {
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

        _billingState.update { it.copy(isPurchaseInProgress = true, errorMessage = null) }
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
        queryPurchases()
    }

    private fun updateProductListState() {
        externalScope.launch {
            val currentPrefs = userPreferencesDataSource.observeUserPreferences().first()
            val currentTier = SupporterTier.fromSkuOrTitle(currentPrefs.supporterTier)
                ?: if (currentPrefs.isSupporter) SupporterTier.GENEROUS_SUPPORTER else null
            val currentOrdinal = currentTier?.ordinal ?: -1

            val productsUi = SupporterTier.entries.map { tier ->
                val details = cachedProductDetails[tier.skuId]
                val formattedPrice = details?.oneTimePurchaseOfferDetails?.formattedPrice
                    ?: when (tier) {
                        SupporterTier.COFFEE -> "₹99 / $1.99"
                        SupporterTier.GENEROUS_SUPPORTER -> "₹299 / $4.99"
                        SupporterTier.PATRON -> "₹999 / $14.99"
                    }

                val isOwned = currentOrdinal >= tier.ordinal

                TipProductUi(
                    tier = tier,
                    formattedPrice = formattedPrice,
                    isOwned = isOwned
                )
            }

            _billingState.update {
                it.copy(products = productsUi)
            }
        }
    }

    fun destroy() {
        if (isServiceConnected) {
            billingClient.endConnection()
            isServiceConnected = false
        }
    }
}
