package com.alphelios.iap

/**
 * Establishes communication bridge between caller and [IapConnector].
 * [onProductsPurchased] provides recent purchases
 * [onPurchaseAcknowledged] provides a callback after a purchase is acknowledged
 * [onError] is used to notify caller about possible errors.
 */
interface InAppEventsListener {
    fun onInAppProductsFetched(skuDetailsList: List<DataWrappers.SkuInfo>)
    fun onServiceConnect(connected: Boolean)
    fun onPurchaseAcknowledged(purchase: DataWrappers.PurchaseInfo)
    fun onProductsPurchased(purchases: List<DataWrappers.PurchaseInfo>)
    fun onError(inAppConnector: IapConnector, result: DataWrappers.BillingResponse? = null)
}