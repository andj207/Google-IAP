package com.alphelios.iap

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode.*
import com.android.billingclient.api.BillingClient.FeatureType.SUBSCRIPTIONS
import com.android.billingclient.api.BillingClient.SkuType.INAPP
import com.android.billingclient.api.BillingClient.SkuType.SUBS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Wrapper class for Google In-App purchases.
 * Handles vital processes while dealing with IAP.
 * Works around a listener [InAppEventsListener] for delivering events back to the caller.
 */
class IapConnector(context: Context, private val base64Key: String) {
    private var shouldAutoAcknowledge: Boolean = false
    private var fetchedSkuDetailsList = mutableListOf<SkuDetails>()
    private val tag = "InAppLog"
    private var inAppEventsListener: InAppEventsListener? = null

    private var inAppIds: List<String>? = null
    private var subIds: List<String>? = null
    private var consumableIds: List<String> = listOf()

    private lateinit var iapClient: BillingClient

    init {
        init(context)
    }

    /**
     * To set INAPP product IDs.
     */
    fun setInAppProductIds(inAppIds: List<String>): IapConnector {
        this.inAppIds = inAppIds
        return this
    }

    /**
     * To set consumable product IDs.
     * Rest of the IDs will be considered non-consumable.
     */
    fun setConsumableProductIds(consumableIds: List<String>): IapConnector {
        this.consumableIds = consumableIds
        return this
    }

    /**
     * Iap will auto acknowledge the purchase
     */
    fun autoAcknowledge(): IapConnector {
        shouldAutoAcknowledge = true
        return this
    }

    /**
     * To set SUBS product IDs.
     */
    fun setSubscriptionIds(subIds: List<String>): IapConnector {
        this.subIds = subIds
        return this
    }

    /**
     * Called to purchase an item.
     * Its result is received in [PurchasesUpdatedListener] which further is handled
     * by [handleConsumableProducts] / [handleNonConsumableProducts].
     */
    fun makePurchase(activity: Activity, sku: String) {
        if (fetchedSkuDetailsList.isEmpty())
            inAppEventsListener?.onError(this, DataWrappers.BillingResponse("Products not fetched"))
        else {
            val skuInfo = fetchedSkuDetailsList.firstOrNull { it.sku == sku }
            if (skuInfo != null) {
                val result = iapClient.launchBillingFlow(
                    activity,
                    BillingFlowParams.newBuilder()
                        .setSkuDetails(skuInfo).build()
                )
                if (result.responseCode != 0) {
                    inAppEventsListener?.onError(
                        this,
                        DataWrappers.BillingResponse("Billing flow error")
                    )
                }
            } else {
                inAppEventsListener?.onError(
                    this,
                    DataWrappers.BillingResponse("Product not found")
                )
            }
        }
    }

    /**
     * To attach an event listener to establish a bridge with the caller.
     */
    fun setOnInAppEventsListener(inAppEventsListener: InAppEventsListener) {
        this.inAppEventsListener = inAppEventsListener
    }

    /**
     * To initialise IapConnector.
     */
    private fun init(context: Context) {
        iapClient = BillingClient.newBuilder(context)
            .enablePendingPurchases()
            .setListener { billingResult, purchases ->
                /**
                 * Only recent purchases are received here
                 */

                when (billingResult.responseCode) {
                    OK -> purchases?.let { processPurchases(purchases) }
                    ITEM_ALREADY_OWNED -> inAppEventsListener?.onError(
                        this,
                        billingResult.run {
                            DataWrappers.BillingResponse(
                                debugMessage,
                                responseCode
                            )
                        }
                    )
                    SERVICE_DISCONNECTED -> connect()
                    else -> Timber.d("Purchase update : ${billingResult.debugMessage}")
                }
            }.build()
    }

    /**
     * Connects billing client with Play console to start working with IAP.
     */
    fun connect(): IapConnector {
        Timber.d("Billing service : Connecting...")
        if (!iapClient.isReady) {
            iapClient.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    inAppEventsListener?.onServiceConnect(false)
                }

                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        OK -> {
                            Timber.d("Billing service : Connected")
                            GlobalScope.launch(Dispatchers.IO) {
                                querySkuList()
                                inAppEventsListener?.onInAppProductsFetched(
                                    fetchedSkuDetailsList.toMutableList().map {
                                        getSkuInfo(it).also { si ->
                                            si.isConsumable = consumableIds.contains(si.sku)
                                        }
                                    }
                                )
                                inAppEventsListener?.onServiceConnect(true)
                            }
                        }
                        BILLING_UNAVAILABLE -> {
                            Timber.d("Billing service : Unavailable")
                            inAppEventsListener?.onServiceConnect(false)
                        }
                        else -> {
                            Timber.d("Billing service : Setup error")
                            inAppEventsListener?.onServiceConnect(false)
                        }
                    }
                }
            })
        }
        return this
    }

    fun disconnect() {
        iapClient.endConnection()
    }

    fun isInitialized(): Boolean {
        return iapClient.isReady
    }

    /**
     * Fires a query in Play console to get [SkuDetails] for provided type and IDs.
     */
    private suspend fun querySkuList() {
        var skuResult = inAppIds?.let {
            iapClient.querySkuDetails(
                SkuDetailsParams.newBuilder()
                    .setSkusList(it).setType(INAPP).build()
            )
        }

        skuResult?.skuDetailsList?.let {
            fetchedSkuDetailsList.addAll(it)
        }

        skuResult?.billingResult?.let {
            if (it.responseCode != 0) {
                Timber.e("Billing query INAPP sku list error ${it.debugMessage}")
            }
        }

        skuResult = subIds?.let {
            iapClient.querySkuDetails(
                SkuDetailsParams.newBuilder()
                    .setSkusList(it).setType(SUBS).build()
            )
        }

        skuResult?.skuDetailsList?.let {
            fetchedSkuDetailsList.addAll(it)
        }

        skuResult?.billingResult?.let {
            if (it.responseCode != 0) {
                Timber.e("Billing query SUBS sku list error ${it.debugMessage}")
            }
        }
    }

    private fun getSkuInfo(skuDetails: SkuDetails): DataWrappers.SkuInfo {
        return DataWrappers.SkuInfo(
            skuDetails.sku,
            skuDetails.description,
            skuDetails.freeTrialPeriod,
            skuDetails.iconUrl,
            skuDetails.introductoryPrice,
            skuDetails.introductoryPriceAmountMicros,
            skuDetails.introductoryPriceCycles,
            skuDetails.introductoryPricePeriod,
            skuDetails.originalJson,
            skuDetails.originalPrice,
            skuDetails.originalPriceAmountMicros,
            skuDetails.price,
            skuDetails.priceAmountMicros,
            skuDetails.priceCurrencyCode,
            skuDetails.subscriptionPeriod,
            skuDetails.title,
            skuDetails.type
        )
    }

    /**
     * Returns all the **non-consumable** purchases of the user.
     */
    fun getAllPurchases() {
        if (iapClient.isReady) {
            val allPurchases = mutableListOf<Purchase>()
            iapClient.queryPurchases(INAPP).purchasesList?.let { allPurchases.addAll(it) }
            if (isSubSupportedOnDevice())
                iapClient.queryPurchases(SUBS).purchasesList?.let { allPurchases.addAll(it) }
            processPurchases(allPurchases)
        } else {
            inAppEventsListener?.onError(
                this,
                DataWrappers.BillingResponse("Client not initialized yet.")
            )
        }
    }

    /**
     * Checks purchase signature for more security.
     */
    private fun processPurchases(allPurchases: List<Purchase>) {
        if (allPurchases.isNotEmpty()) {
            val validPurchases = allPurchases.filter {
                isPurchaseSignatureValid(it) && fetchedSkuDetailsList.any { skuInfo -> skuInfo.sku == it.sku }
            }.map { purchase ->
                DataWrappers.PurchaseInfo(
                    getSkuInfo(fetchedSkuDetailsList.first { it.sku == purchase.sku }),
                    purchase.purchaseState,
                    purchase.developerPayload,
                    purchase.isAcknowledged,
                    purchase.isAutoRenewing,
                    purchase.orderId,
                    purchase.originalJson,
                    purchase.packageName,
                    purchase.purchaseTime,
                    purchase.purchaseToken,
                    purchase.signature,
                    purchase.sku,
                    purchase.accountIdentifiers
                )
            }

            inAppEventsListener?.onProductsPurchased(validPurchases)

            if (shouldAutoAcknowledge)
                validPurchases.forEach {
                    acknowledgePurchase(it)
                }
        } else {
            inAppEventsListener?.onProductsPurchased(emptyList())
        }
    }

    /**
     * Purchase of non-consumable products must be acknowledged to Play console.
     * This will avoid refunding for these products to users by Google.
     *
     * Consumable products might be brought/consumed by users multiple times (for eg. diamonds, coins).
     * Hence, it is necessary to notify Play console about such products.
     */
    private fun acknowledgePurchase(purchase: DataWrappers.PurchaseInfo) {
        purchase.run {
            if (consumableIds.contains(sku))
                iapClient.consumeAsync(
                    ConsumeParams.newBuilder()
                        .setPurchaseToken(purchaseToken).build()
                ) { billingResult, _ ->
                    when (billingResult.responseCode) {
                        OK -> inAppEventsListener?.onPurchaseAcknowledged(this)
                        else -> {
                            Timber.d("Handling consumables : Error -> ${billingResult.debugMessage}")
                            inAppEventsListener?.onError(
                                this@IapConnector,
                                billingResult.run {
                                    DataWrappers.BillingResponse(
                                        debugMessage,
                                        responseCode
                                    )
                                }
                            )
                        }
                    }
                } else
                iapClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(
                        purchaseToken
                    ).build()
                ) { billingResult ->
                    when (billingResult.responseCode) {
                        OK -> inAppEventsListener?.onPurchaseAcknowledged(this)
                        else -> {
                            Timber.d("Handling non consumables : Error -> ${billingResult.debugMessage}")
                            inAppEventsListener?.onError(
                                this@IapConnector,
                                billingResult.run {
                                    DataWrappers.BillingResponse(
                                        debugMessage,
                                        responseCode
                                    )
                                }
                            )
                        }
                    }
                }
        }
    }

    /**
     * Before using subscriptions, device-support must be checked.
     */
    private fun isSubSupportedOnDevice(): Boolean {
        var isSupported = false
        when (iapClient.isFeatureSupported(SUBSCRIPTIONS).responseCode) {
            OK -> {
                isSupported = true
                Timber.d("Subs support check : Success")
            }
            SERVICE_DISCONNECTED -> connect()
            else -> Timber.d("Subs support check : Error")
        }
        return isSupported
    }

    /**
     * Checks purchase signature validity
     */
    private fun isPurchaseSignatureValid(purchase: Purchase): Boolean {
        return Security.verifyPurchase(
            base64Key, purchase.originalJson, purchase.signature
        )
    }
}