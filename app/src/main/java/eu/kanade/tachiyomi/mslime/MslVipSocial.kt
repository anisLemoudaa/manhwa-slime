package eu.kanade.tachiyomi.mslime

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Server-confirmed membership data. Local cache is used only to avoid showing ads, never for paid access. */
data class MslVipStatus(
    val active: Boolean = false,
    val plan: String? = null,
    val expiresAt: String? = null,
)

data class MslVipPurchaseResult(
    val success: Boolean,
    val reason: String? = null,
    val balance: Int? = null,
    val expiresAt: String? = null,
)

object MslVip {
    private const val PREFS = "msl_vip_server_cache"
    private const val KEY_USER = "verified_user"
    private const val KEY_ACTIVE = "verified_active"
    private const val KEY_EXPIRY = "verified_expiry"

    fun cachedActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_USER, null) != MslSupabase.uid(context) || !prefs.getBoolean(KEY_ACTIVE, false)) return false
        val expiry = prefs.getString(KEY_EXPIRY, null)
        return expiry.isNullOrBlank() || runCatching { Instant.parse(expiry).isAfter(Instant.now()) }.getOrDefault(false)
    }

    /** Fetches entitlement from Supabase; failure never grants membership. */
    fun refresh(context: Context): MslVipStatus? {
        return try {
            val token = MslSupabase.token(context) ?: return null
            val response = MslSupabase.call("POST", "/rest/v1/rpc/get_my_vip_status", "{}", token)
            if (response.first !in 200..299) return null
            val json = JSONObject(response.second)
            val status = MslVipStatus(
                active = json.optBoolean("active", false),
                plan = json.optString("plan").takeUnless { it == "null" || it.isBlank() },
                expiresAt = json.optString("expires_at").takeUnless { it == "null" || it.isBlank() },
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER, MslSupabase.uid(context))
                .putBoolean(KEY_ACTIVE, status.active)
                .putString(KEY_EXPIRY, status.expiresAt)
                .apply()
            status
        } catch (_: Exception) {
            null
        }
    }

    fun purchase(context: Context, plan: String): MslVipPurchaseResult? {
        return try {
            val token = MslSupabase.token(context) ?: return null
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/purchase_vip",
                JSONObject().put("p_plan", plan).put("p_request_id", UUID.randomUUID().toString()).toString(),
                token,
            )
            if (response.first !in 200..299) return null
            val result = JSONObject(response.second)
            MslVipPurchaseResult(
                success = result.optBoolean("success"),
                reason = result.optString("reason").takeUnless { it == "null" || it.isBlank() },
                balance = result.optInt("balance", -1).takeIf { it >= 0 },
                expiresAt = result.optString("expires_at").takeUnless { it == "null" || it.isBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }
}

data class MslDailyRewardResult(
    val success: Boolean,
    val claimed: Boolean,
    val alreadyClaimed: Boolean,
    val day: Int,
    val reward: Int,
    val balance: Int?,
)

object MslDailyRewards {
    val rewards = listOf(30, 45, 60, 90, 120, 165, 200)

    fun claim(context: Context): MslDailyRewardResult? {
        return try {
            val token = MslSupabase.token(context) ?: return null
            val response = MslSupabase.call("POST", "/rest/v1/rpc/claim_daily_login_reward", "{}", token)
            if (response.first !in 200..299) return null
            val json = JSONObject(response.second)
            MslDailyRewardResult(
                success = json.optBoolean("success"),
                claimed = json.optBoolean("claimed"),
                alreadyClaimed = json.optBoolean("already_claimed"),
                day = json.optInt("day", 0),
                reward = json.optInt("reward", 0),
                balance = json.optInt("balance", -1).takeIf { it >= 0 },
            ).also { result ->
                if (result.balance != null) MslWallet.updateBalance(result.balance)
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class MslSocialMetrics(
    val average: Double,
    val ratingCount: Int,
    val views: Int,
    val myRating: Int,
)

object MslSocial {
    /** Stable source URL based key; hashes the URL so no source URL is sent or stored as a title identifier. */
    fun titleKey(kind: String, sourceId: String, itemUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(itemUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val safeSource = sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        return "${kind.take(12)}:$safeSource:$digest"
    }

    fun openedMetrics(context: Context, titleKey: String): MslSocialMetrics? {
        val token = MslSupabase.token(context) ?: return null
        val encodedKey = JSONObject.quote(titleKey)
        MslSupabase.call("POST", "/rest/v1/rpc/record_title_view", "{\"p_title_key\":$encodedKey}", token)
        return metrics(context, titleKey, token)
    }

    fun metrics(context: Context, titleKey: String, tokenOverride: String? = null): MslSocialMetrics? {
        return try {
            val token = tokenOverride ?: MslSupabase.token(context)
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/get_title_metrics",
                JSONObject().put("p_title_key", titleKey).toString(),
                token,
            )
            if (response.first !in 200..299) return null
            val result = JSONObject(response.second)
            MslSocialMetrics(
                average = result.optDouble("average", 0.0),
                ratingCount = result.optInt("rating_count", 0),
                views = result.optInt("views", 0),
                myRating = result.optInt("my_rating", 0),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun rate(context: Context, titleKey: String, rating: Int): MslSocialMetrics? {
        return try {
            val token = MslSupabase.token(context) ?: return null
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/rate_title",
                JSONObject().put("p_title_key", titleKey).put("p_rating", rating).toString(),
                token,
            )
            if (response.first !in 200..299) return null
            val result = JSONObject(response.second)
            MslSocialMetrics(
                average = result.optDouble("average", 0.0),
                ratingCount = result.optInt("rating_count", 0),
                views = result.optInt("views", 0),
                myRating = result.optInt("my_rating", 0),
            )
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun MslSocialStats(titleKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metrics by remember(titleKey) { mutableStateOf<MslSocialMetrics?>(null) }
    var loading by remember(titleKey) { mutableStateOf(true) }
    var message by remember(titleKey) { mutableStateOf("") }

    LaunchedEffect(titleKey) {
        loading = true
        metrics = withContext(Dispatchers.IO) { MslSocial.openedMetrics(context, titleKey) }
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        border = BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("إحصاءات مستخدمي Manhwa Slime", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("المشاهدات داخل التطبيق", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(metrics?.views?.toString() ?: if (loading) "…" else "—", color = MslDesignTokens.accentBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("تقييم القراء", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(
                        metrics?.let { if (it.ratingCount == 0) "—" else "%.1f (${it.ratingCount})".format(Locale.ROOT, it.average, it.ratingCount) }
                            ?: if (loading) "…" else "—",
                        color = MslDesignTokens.accentBright,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("قيّم هذا العنوان:", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                (1..5).forEach { value ->
                    Text(
                        text = if (value <= (metrics?.myRating ?: 0)) "★" else "☆",
                        color = Color(0xFFFFC857),
                        fontSize = 25.sp,
                        modifier = Modifier.clickable {
                            scope.launch {
                                val updated = withContext(Dispatchers.IO) { MslSocial.rate(context, titleKey, value) }
                                if (updated != null) {
                                    metrics = updated
                                    message = "تم حفظ تقييمك داخل التطبيق"
                                } else {
                                    message = "تعذّر حفظ التقييم؛ حاول مجددًا"
                                }
                            }
                        },
                    )
                }
            }
            Text("المشاهدات: مرة واحدة لكل حساب في اليوم؛ التقييم من مستخدمي التطبيق فقط.", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelSmall)
            if (message.isNotBlank()) Text(message, color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun MslDailyRewardCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<MslDailyRewardResult?>(null) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        loading = true
        result = withContext(Dispatchers.IO) { MslDailyRewards.claim(context) }
        loading = false
        message = when {
            result?.claimed == true -> "مكافأة اليوم ${result?.day}: +${result?.reward} عملة"
            result?.alreadyClaimed == true -> "استلمت مكافأة اليوم ${result?.day} بالفعل"
            else -> "تعذّر التحقق من مكافأة الدخول"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
        border = BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("مكافأة الدخول اليومية", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold)
                Text("30 • 45 • 60 • 90 • 120 • 165 • 200 عملة", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(if (loading) "جارٍ التحقق…" else message, color = MslDesignTokens.accentBright, style = MaterialTheme.typography.bodySmall)
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MslDesignTokens.accentBright)
            } else if (result == null) {
                TextButton(onClick = {
                    scope.launch {
                        loading = true
                        result = withContext(Dispatchers.IO) { MslDailyRewards.claim(context) }
                        loading = false
                        message = when {
                            result?.claimed == true -> "مكافأة اليوم ${result?.day}: +${result?.reward} عملة"
                            result?.alreadyClaimed == true -> "استلمت مكافأة اليوم ${result?.day} بالفعل"
                            else -> "تعذّر التحقق؛ حاول لاحقًا"
                        }
                    }
                }) { Text("إعادة المحاولة", color = MslDesignTokens.accentBright) }
            }
        }
    }
}

private data class VipPlan(val id: String, val title: String, val duration: String, val price: Int, val description: String)

private val vipPlans = listOf(
    VipPlan("month", "VIP شهري", "شهر واحد", 500, "امتيازات VIP لمدة شهر"),
    VipPlan("six_months", "VIP ستة أشهر", "6 أشهر", 1600, "امتيازات VIP لمدة ستة أشهر"),
    VipPlan("lifetime", "VIP مدى الحياة", "دائم", 8000, "عضوية دائمة لا تنتهي"),
)

class MslVipScreen : Screen {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<MslVipStatus?>(null) }
        var balance by remember { mutableStateOf<Int?>(null) }
        var loading by remember { mutableStateOf(true) }
        var selectedPlan by remember { mutableStateOf<VipPlan?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }

        LaunchedEffect(context) {
            loading = true
            status = withContext(Dispatchers.IO) { MslVip.refresh(context) }
            balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
            loading = false
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("عضوية VIP") },
                    navigationIcon = {
                        TextButton(onClick = { navigator.pop() }) { Text("رجوع", color = MslDesignTokens.accentBright) }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MslDesignTokens.cardShape,
                    colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                    border = BorderStroke(1.dp, MslDesignTokens.accent.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (status?.active == true) "👑 عضويتك نشطة" else "ارتقِ إلى VIP", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MslDesignTokens.accentBright)
                        Text(
                            when {
                                loading -> "جارٍ التحقق من العضوية…"
                                status?.active == true && status?.plan == "lifetime" -> "عضوية مدى الحياة"
                                status?.active == true -> "تنتهي في ${formatVipDate(status?.expiresAt)}"
                                status == null -> "تعذّر التحقق من حالة العضوية؛ أعد فتح الصفحة عند توفر الاتصال."
                                else -> "لا توجد عضوية نشطة حاليًا"
                            },
                            color = MslDesignTokens.textSecondary,
                        )
                        Text("الرصيد: ${balance?.let { "$it عملة" } ?: "—"}", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }

                Text("مزايا VIP", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("شارة VIP بجانب الاسم والتعليقات • تنزيل الفصول بلا عملات • تجاوز الإعلانات المعروضة • تثبيت أحدث تعليق لك على العنوان.", color = MslDesignTokens.textSecondary)
                Text("القراءة عبر الإنترنت مجانية حاليًا للجميع.", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelSmall)

                Text("اختر باقتك", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                vipPlans.forEach { plan ->
                    val alreadyLifetime = status?.active == true && status?.plan == "lifetime"
                    OutlinedButton(
                        onClick = { selectedPlan = plan },
                        enabled = !busy && !loading && status != null && MslWallet.enabled && !alreadyLifetime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MslDesignTokens.compactCardShape,
                        border = BorderStroke(1.dp, MslDesignTokens.accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MslDesignTokens.accentBright),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(plan.title, fontWeight = FontWeight.Bold)
                                Text(plan.duration, style = MaterialTheme.typography.labelSmall, color = MslDesignTokens.textSecondary)
                            }
                            Text("${plan.price} عملة", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("المكافآت الأسبوعية: ${MslDailyRewards.rewards.joinToString(" • ")} عملة، ثم تعود إلى اليوم الأول.", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelSmall)
                if (!MslWallet.enabled) Text("المحفظة غير متاحة في هذا البناء.", color = MaterialTheme.colorScheme.error)
                if (message.isNotBlank()) Text(message, color = MslDesignTokens.textSecondary)
            }
        }

        selectedPlan?.let { plan ->
            AlertDialog(
                onDismissRequest = { if (!busy) selectedPlan = null },
                title = { Text("تأكيد اشتراك VIP") },
                text = { Text("سيُخصم ${plan.price} عملة من رصيدك مقابل ${plan.duration}. هل تريد المتابعة؟") },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) { MslVip.purchase(context, plan.id) }
                            balance = withContext(Dispatchers.IO) { MslWallet.refresh(context) }
                            status = withContext(Dispatchers.IO) { MslVip.refresh(context) }
                            message = when {
                                result == null -> "تعذّر إتمام الاشتراك؛ لم يتم تأكيد أي خصم. تحقق من الاتصال والرصيد."
                                result.success -> "تم تفعيل عضوية ${plan.title} بنجاح."
                                result.reason == "insufficient_coins" -> "رصيدك لا يكفي لهذه الباقة."
                                result.reason == "lifetime_active" -> "لديك عضوية مدى الحياة نشطة بالفعل."
                                else -> "تعذّر تفعيل العضوية. لم يتم خصم العملات."
                            }
                            busy = false
                            selectedPlan = null
                        }
                    }) { Text(if (busy) "جارٍ التنفيذ…" else "تأكيد الخصم") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { selectedPlan = null }) { Text("إلغاء") } },
            )
        }
    }
}

private fun formatVipDate(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale("ar"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault(value.take(10))
}
