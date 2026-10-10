package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import eu.kanade.tachiyomi.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Play Billing client. Product IDs must be created as one-time consumable products in Play Console. */
object MslPlayBilling {
    private val products = listOf(
        "gold_coins_15" to 15,
        "gold_coins_40" to 40,
        "gold_coins_90" to 90,
        "gold_coins_200" to 200,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: BillingClient? = null
    private var cachedDetails: Map<String, ProductDetails> = emptyMap()
    private var purchaseContext: Context? = null
    private var purchaseStatus: ((String) -> Unit)? = null
    private var purchaseBalanceChanged: ((Int) -> Unit)? = null

    fun loadProducts(
        activity: Activity,
        onProducts: (List<CoinPack>) -> Unit,
        onStatus: (String) -> Unit,
        onBalanceChanged: (Int) -> Unit,
    ) {
        if (!BuildConfig.COIN_WALLET_ENABLED) {
            onProducts(emptyList())
            onStatus(activity.stringResource(MR.strings.coin_store_disabled))
            return
        }
        purchaseContext = activity.applicationContext
        purchaseStatus = onStatus
        purchaseBalanceChanged = onBalanceChanged
        val billing = client ?: createClient(activity.applicationContext).also { client = it }
        val query = {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    products.map { (id, _) ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                )
                .build()
            billing.queryProductDetailsAsync(params) { result, response ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onProducts(emptyList())
                    onStatus(activity.stringResource(MR.strings.coin_billing_products_failed, result.responseCode))
                    return@queryProductDetailsAsync
                }
                val found = response.productDetailsList.associateBy { it.productId }
                cachedDetails = found
                val packs = products.mapNotNull { (id, amount) ->
                    val detail = found[id] ?: return@mapNotNull null
                    val price = detail.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
                    CoinPack(id, amount, price)
                }
                onProducts(packs)
                if (packs.isEmpty()) onStatus(activity.stringResource(MR.strings.coin_billing_empty))
            }
            billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { purchaseResult, purchases ->
                if (purchaseResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.forEach(::verifyPurchase)
                }
            }
        }
        if (billing.isReady) {
            query()
        } else {
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) query()
                    else {
                        onProducts(emptyList())
                        onStatus(activity.stringResource(MR.strings.coin_billing_connect_failed, result.responseCode))
                    }
                }
                override fun onBillingServiceDisconnected() {
                    onStatus(activity.stringResource(MR.strings.coin_billing_disconnected))
                }
            })
        }
    }

    fun buy(activity: Activity, productId: String, onStatus: (String) -> Unit) {
        val billing = client
        val detail = cachedDetails[productId]
        if (billing == null || !billing.isReady || detail == null) {
            onStatus(activity.stringResource(MR.strings.coin_purchase_unavailable))
            return
        }
        val userId = MslSupabase.uid(activity)
        if (userId.isBlank()) {
            onStatus(activity.stringResource(MR.strings.coin_purchase_account_error))
            return
        }
        val item = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(detail)
            .build()
        val accountHash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(item))
            .setObfuscatedAccountId(accountHash)
            .build()
        onStatus(activity.stringResource(MR.strings.coin_purchase_opening))
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onStatus(activity.stringResource(MR.strings.coin_purchase_start_failed, result.responseCode))
        }
    }

    fun clearCallbacks() {
        purchaseContext = null
        purchaseStatus = null
        purchaseBalanceChanged = null
    }

    private fun createClient(context: Context): BillingClient {
        val listener = PurchasesUpdatedListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::verifyPurchase)
                BillingClient.BillingResponseCode.USER_CANCELED -> purchaseStatus?.invoke(context.stringResource(MR.strings.coin_purchase_canceled))
                else -> purchaseStatus?.invoke(context.stringResource(MR.strings.coin_purchase_failed, result.responseCode))
            }
        }
        return BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
    }

    private fun verifyPurchase(purchase: com.android.billingclient.api.Purchase) {
        val context = purchaseContext ?: return
        if (purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PENDING) {
            purchaseStatus?.invoke(context.stringResource(MR.strings.coin_purchase_pending))
            return
        }
        if (purchase.purchaseState != com.android.billingclient.api.Purchase.PurchaseState.PURCHASED) return
        val productId = purchase.products.firstOrNull { id -> products.any { it.first == id } } ?: return
        purchaseStatus?.invoke(context.stringResource(MR.strings.coin_purchase_verifying))
        scope.launch {
            val result = runCatching { MslWallet.verifyPlayPurchase(context, productId, purchase.purchaseToken) }.getOrNull()
            withContext(Dispatchers.Main) {
                result?.let { purchaseBalanceChanged?.invoke(it) }
                purchaseStatus?.invoke(
                    if (result != null) context.stringResource(MR.strings.coin_purchase_verified)
                    else context.stringResource(MR.strings.coin_purchase_retry),
                )
            }
        }
    }
}

data class CoinPack(val productId: String, val coins: Int, val formattedPrice: String)
