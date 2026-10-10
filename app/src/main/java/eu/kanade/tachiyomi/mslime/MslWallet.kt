package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

/** Client calls only user-scoped RPCs. Granting ad or purchase coins is server-only. */
object MslWallet {
    private val _balance = MutableStateFlow<Int?>(null)
    val balance = _balance.asStateFlow()

    val enabled: Boolean get() = BuildConfig.COIN_WALLET_ENABLED

    internal fun updateBalance(value: Int) {
        _balance.value = value
    }

    data class RewardSession(val id: String, val userId: String)
    data class DownloadReservation(val allowed: Boolean, val balance: Int, val status: String)
    data class CoinTransaction(val id: String, val delta: Int, val kind: String, val createdAt: String, val referenceId: String? = null)

    fun refresh(context: Context): Int? {
        val current = runCatching {
            val token = MslSupabase.token(context) ?: return@runCatching null
            if (MslSupabase.uid(context).isBlank()) return@runCatching null
            retryPendingCommits(context, token)
            retryPendingRefunds(context, token)
            val response = MslSupabase.call("POST", "/rest/v1/rpc/ensure_coin_wallet", "{}", token)
            if (response.first !in 200..299) return@runCatching null
            response.second.trim().trim('"').toIntOrNull()
        }.getOrNull()
        if (current != null) _balance.value = current
        return current ?: _balance.value
    }

    /** Returns this authenticated user's server ledger, or null when the service is unavailable. */
    fun transactions(context: Context): List<CoinTransaction>? {
        return try {
            val token = MslSupabase.token(context) ?: return null
            val userId = MslSupabase.uid(context).takeIf(String::isNotBlank) ?: return null
            val query = "select=id,delta,kind,created_at,reference_id&user_id=eq.$userId&order=created_at.desc&limit=50"
            val response = MslSupabase.call(
                "GET",
                "/rest/v1/coin_transactions?$query",
                null,
                token,
            )
            if (response.first !in 200..299) return null
            val rows = JSONArray(response.second)
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                CoinTransaction(
                    id = row.getString("id"),
                    delta = row.getInt("delta"),
                    kind = row.getString("kind"),
                    createdAt = row.getString("created_at"),
                    referenceId = row.optString("reference_id").takeUnless { it == "null" || it.isBlank() },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun beginReward(context: Context): RewardSession? {
        val token = MslSupabase.token(context) ?: return null
        val userId = MslSupabase.uid(context).takeIf(String::isNotBlank) ?: return null
        val sessionId = UUID.randomUUID().toString()
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/begin_ad_reward",
            JSONObject().put("p_session_id", sessionId).toString(),
            token,
        )
        if (response.first !in 200..299) return null
        return RewardSession(sessionId, userId)
    }

    fun rewardStatus(context: Context, sessionId: String): String? {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call(
            "GET",
            "/rest/v1/ad_reward_sessions?select=status&id=eq.$sessionId&limit=1",
            null,
            token,
        )
        if (response.first !in 200..299) return null
        val rows = JSONArray(response.second)
        return rows.optJSONObject(0)?.optString("status")
    }

    fun reserveDownload(context: Context, reservationId: String, chapterKey: String): DownloadReservation? {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/reserve_download_coin",
            JSONObject()
                .put("p_reservation_id", reservationId)
                .put("p_chapter_key", chapterKey)
                .toString(),
            token,
        )
        if (response.first !in 200..299) return null
        val result = JSONObject(response.second)
        val reservation = DownloadReservation(
            allowed = result.optBoolean("allowed"),
            balance = result.optInt("balance", -1),
            status = result.optString("status"),
        )
        if (reservation.balance >= 0) _balance.value = reservation.balance
        return reservation
    }

    fun commitDownload(context: Context, reservationId: String): Boolean {
        val committed = runCatching {
            rpcBoolean(
                context,
                "commit_download_coin",
                JSONObject().put("p_reservation_id", reservationId).toString(),
            )
        }.getOrDefault(false)
        if (committed) removeQueuedCommit(context, reservationId) else queueCommit(context, reservationId)
        return committed
    }

    fun refundDownload(context: Context, reservationId: String): Boolean {
        val token = runCatching { MslSupabase.token(context) }.getOrNull()
        if (token == null) {
            queueRefund(context, reservationId)
            return false
        }
        val response = runCatching {
            MslSupabase.call(
                "POST",
                "/rest/v1/rpc/refund_download_coin",
                JSONObject().put("p_reservation_id", reservationId).toString(),
                token,
            )
        }.getOrNull()
        if (response == null || response.first !in 200..299) {
            queueRefund(context, reservationId)
            return false
        }
        removeQueuedRefund(context, reservationId)
        val refunded = response.second.trim().equals("true", ignoreCase = true)
        if (refunded) refresh(context)
        return refunded
    }

    private fun retryPendingCommits(context: Context, token: String) {
        val pending = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
            .getStringSet("pending_commits", emptySet())?.toList().orEmpty()
        pending.forEach { id ->
            val response = runCatching {
                MslSupabase.call(
                    "POST",
                    "/rest/v1/rpc/commit_download_coin",
                    JSONObject().put("p_reservation_id", id).toString(),
                    token,
                )
            }.getOrNull()
            if (response != null && response.first in 200..299 &&
                response.second.trim().equals("true", ignoreCase = true)
            ) {
                removeQueuedCommit(context, id)
            }
        }
    }

    private fun queueCommit(context: Context, id: String) {
        val preferences = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
        val ids = preferences.getStringSet("pending_commits", emptySet()).orEmpty().toMutableSet()
        ids.add(id)
        preferences.edit().putStringSet("pending_commits", ids).apply()
    }

    private fun removeQueuedCommit(context: Context, id: String) {
        val preferences = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
        val ids = preferences.getStringSet("pending_commits", emptySet()).orEmpty().toMutableSet()
        ids.remove(id)
        preferences.edit().putStringSet("pending_commits", ids).apply()
    }

    private fun retryPendingRefunds(context: Context, token: String) {
        val pending = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
            .getStringSet("pending_refunds", emptySet())?.toList().orEmpty()
        pending.forEach { id ->
            val result = runCatching {
                MslSupabase.call(
                    "POST",
                    "/rest/v1/rpc/refund_download_coin",
                    JSONObject().put("p_reservation_id", id).toString(),
                    token,
                )
            }.getOrNull()
            if (result != null && result.first in 200..299) removeQueuedRefund(context, id)
        }
    }

    private fun queueRefund(context: Context, id: String) {
        val preferences = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
        val ids = preferences.getStringSet("pending_refunds", emptySet()).orEmpty().toMutableSet()
        ids.add(id)
        preferences.edit().putStringSet("pending_refunds", ids).apply()
    }

    private fun removeQueuedRefund(context: Context, id: String) {
        val preferences = context.getSharedPreferences("msl_coin_wallet", Context.MODE_PRIVATE)
        val ids = preferences.getStringSet("pending_refunds", emptySet()).orEmpty().toMutableSet()
        ids.remove(id)
        preferences.edit().putStringSet("pending_refunds", ids).apply()
    }

    private fun rpcBoolean(context: Context, name: String, body: String): Boolean {
        val token = MslSupabase.token(context) ?: return false
        val response = MslSupabase.call("POST", "/rest/v1/rpc/$name", body, token)
        return response.first in 200..299 && response.second.trim().equals("true", ignoreCase = true)
    }

    fun verifyPlayPurchase(context: Context, productId: String, purchaseToken: String): Int? {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call(
            "POST",
            "/functions/v1/verify-play-purchase",
            JSONObject()
                .put("productId", productId)
                .put("purchaseToken", purchaseToken)
                .toString(),
            token,
        )
        if (response.first !in 200..299) return null
        val balance = runCatching { JSONObject(response.second).optInt("balance", -1).takeIf { it >= 0 } }.getOrNull()
        if (balance != null) _balance.value = balance
        return balance
    }
}

@Composable
fun MslCoinWalletHeader() {
    if (!MslWallet.enabled) return
    val context = LocalContext.current
    val walletBalance by MslWallet.balance.collectAsState()
    var balance by remember { mutableStateOf<Int?>(null) }
    var balanceLoading by remember { mutableStateOf(true) }
    var storeVisible by remember { mutableStateOf(false) }

    LaunchedEffect(walletBalance) { balance = walletBalance }

    LaunchedEffect(context) {
        balanceLoading = true
        balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
        balanceLoading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        border = BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                storeVisible = true
            }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(MR.strings.coin_wallet_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MslDesignTokens.textSecondary,
                )
                Text(
                    text = if (balanceLoading) "…" else balance?.let { "$it" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MslDesignTokens.accentBright,
                )
            }
            TextButton(
                onClick = { storeVisible = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MslDesignTokens.accentBright),
            ) {
                Text(stringResource(MR.strings.coin_store_button))
            }
        }
    }

    if (storeVisible) {
        MslCoinStoreDialog(
            initialBalance = balance,
            onDismiss = { storeVisible = false },
            onBalanceChanged = { balance = it },
        )
    }
}

@Composable
private fun MslCoinStoreDialog(
    initialBalance: Int?,
    onDismiss: () -> Unit,
    onBalanceChanged: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var balance by remember { mutableStateOf(initialBalance) }
    var packs by remember { mutableStateOf<List<CoinPack>>(emptyList()) }
    var message by remember { mutableStateOf(context.stringResource(MR.strings.coin_store_choose_method)) }
    var busy by remember { mutableStateOf(false) }
    var rewardEarned by remember { mutableStateOf(false) }
    var vipActive by remember { mutableStateOf(MslVip.cachedActive(context)) }
    val closeDialog = {
        MslPlayBilling.clearCallbacks()
        onDismiss()
    }

    LaunchedEffect(activity) {
        balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
        onBalanceChanged(balance)
        vipActive = withContext(Dispatchers.IO) { MslVip.refresh(context)?.active ?: MslVip.cachedActive(context) }
        if (activity != null && BuildConfig.COIN_PLAY_PURCHASES_ENABLED) {
            MslPlayBilling.loadProducts(
                activity,
                onProducts = { packs = it },
                onStatus = { message = it },
                onBalanceChanged = { updatedBalance ->
                    balance = updatedBalance
                    onBalanceChanged(updatedBalance)
                },
            )
        } else if (!BuildConfig.COIN_PLAY_PURCHASES_ENABLED) {
            message = "شراء العملات عبر Google Play غير متاح حتى تفعيل التحقق الخادمي للإيصالات."
        }
    }

    AlertDialog(
        onDismissRequest = closeDialog,
        shape = MslDesignTokens.cardShape,
        containerColor = MslDesignTokens.surface,
        titleContentColor = MslDesignTokens.textPrimary,
        textContentColor = MslDesignTokens.textSecondary,
        title = {
            Text(
                stringResource(MR.strings.coin_store_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    shape = MslDesignTokens.compactCardShape,
                    colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
                    border = BorderStroke(1.dp, MslDesignTokens.border),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            stringResource(MR.strings.coin_wallet_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MslDesignTokens.textSecondary,
                        )
                        Text(
                            balance?.let { context.stringResource(MR.strings.coin_balance_current, it) }
                                ?: stringResource(MR.strings.coin_balance_unavailable),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MslDesignTokens.accentBright,
                        )
                    }
                }
                Text(
                    if (vipActive) "عضوية VIP نشطة؛ لن تُعرض إعلانات المكافآت أثناء الاشتراك."
                    else "أكمل إعلانًا مكافئًا واحدًا لتحصل على 15 عملة بعد تأكيد الخادم.",
                    color = MslDesignTokens.textPrimary,
                )
                Button(
                    enabled = !busy && activity != null && MslWallet.enabled && !vipActive,
                    onClick = {
                        val host = activity ?: return@Button
                        scope.launch {
                            busy = true
                            rewardEarned = false
                            val session = if (BuildConfig.DEBUG) {
                                MslWallet.RewardSession(UUID.randomUUID().toString(), "debug-user")
                            } else {
                                runCatching {
                                    withContext(Dispatchers.IO) { MslWallet.beginReward(context) }
                                }.getOrNull()
                            }
                            if (session == null) {
                                message = context.stringResource(MR.strings.coin_reward_start_error)
                                busy = false
                                return@launch
                            }
                            MslAds.showRewarded(
                                host,
                                session.userId,
                                session.id,
                                onRewardEarned = {
                                    rewardEarned = true
                                    if (BuildConfig.DEBUG) {
                                        message = "تم إكمال إعلان الاختبار؛ لا تُمنح عملات في نسخة Debug."
                                        busy = false
                                        return@showRewarded
                                    }
                                    message = context.stringResource(MR.strings.coin_reward_checking_server)
                                    scope.launch {
                                        for (attempt in 0 until 30) {
                                            delay(2000)
                                            val state = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    MslWallet.rewardStatus(context, session.id)
                                                }
                                            }.getOrNull()
                                            if (state == "credited") {
                                                balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
                                                onBalanceChanged(balance)
                                                message = context.stringResource(MR.strings.coin_reward_added)
                                                busy = false
                                                return@launch
                                            }
                                            if (state == "expired") break
                                        }
                                        balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
                                        onBalanceChanged(balance)
                                        message = context.stringResource(MR.strings.coin_reward_pending)
                                        busy = false
                                    }
                                },
                                onAdClosed = {
                                    if (!rewardEarned) {
                                        message = context.stringResource(MR.strings.coin_reward_closed)
                                        busy = false
                                    }
                                },
                                onAdUnavailable = {
                                    message = context.stringResource(MR.strings.coin_reward_no_ad)
                                    busy = false
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MslDesignTokens.compactCardShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MslDesignTokens.accent,
                        contentColor = MslDesignTokens.textPrimary,
                        disabledContainerColor = MslDesignTokens.surfaceHighest,
                        disabledContentColor = MslDesignTokens.textMuted,
                    ),
                ) {
                    Text(
                        if (busy) {
                            stringResource(
                                MR.strings.coin_reward_checking,
                            )
                        } else {
                            stringResource(MR.strings.coin_reward_watch)
                        },
                    )
                }
                if (!vipActive) {
                    Text(
                        stringResource(MR.strings.coin_reward_unlimited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MslDesignTokens.textMuted,
                    )
                }
                Text(
                    stringResource(MR.strings.coin_play_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MslDesignTokens.textPrimary,
                )
                if (!BuildConfig.COIN_PLAY_PURCHASES_ENABLED) {
                    Text("شراء العملات عبر Google Play غير متاح حاليًا؛ يمكنك جمع العملات من المكافآت اليومية والإعلانات المؤكدة.", color = MslDesignTokens.textSecondary)
                } else if (packs.isEmpty()) {
                    Text(stringResource(MR.strings.coin_play_products_missing), color = MslDesignTokens.textSecondary)
                } else {
                    packs.forEach { pack ->
                        OutlinedButton(
                            enabled = !busy && activity != null,
                            onClick = {
                                activity?.let { host -> MslPlayBilling.buy(host, pack.productId) { message = it } }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MslDesignTokens.compactCardShape,
                            border = BorderStroke(1.dp, MslDesignTokens.accent),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MslDesignTokens.accentBright,
                                disabledContentColor = MslDesignTokens.textMuted,
                            ),
                        ) {
                            Text(stringResource(MR.strings.coin_pack_price, pack.coins, pack.formattedPrice))
                        }
                    }
                }
                Text(
                    stringResource(MR.strings.coin_virtual_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MslDesignTokens.textMuted,
                )
                Text(message, style = MaterialTheme.typography.bodySmall, color = MslDesignTokens.textSecondary)
                if (busy) {
                    CircularProgressIndicator(
                        color = MslDesignTokens.accentBright,
                        trackColor = MslDesignTokens.border,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = closeDialog,
                colors = ButtonDefaults.textButtonColors(contentColor = MslDesignTokens.accentBright),
            ) {
                Text(stringResource(MR.strings.coin_close))
            }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
