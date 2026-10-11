package eu.kanade.tachiyomi.mslime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object MslFollowGraph {
    data class Profile(
        val userId: String,
        val displayName: String,
        val avatarData: String?,
        val coverData: String?,
        val level: Int,
        val rank: String,
        val followerCount: Int,
        val followingCount: Int,
        val isFollowing: Boolean,
    )

    data class User(
        val userId: String,
        val displayName: String,
        val avatarData: String?,
        val level: Int,
        val rank: String,
    )

    enum class ListKind(val label: String, val rpc: String) {
        FOLLOWERS("المتابعون", "list_followers"),
        FOLLOWING("المتابَعون", "list_following"),
    }

    private val revisionCounter = AtomicInteger()
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun token(ctx: android.content.Context): String? = MslSupabase.token(ctx)

    fun profile(ctx: android.content.Context, userId: String): Profile? {
        return runCatching {
            val accessToken = token(ctx) ?: return null
            val result = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/get_public_profile",
                JSONObject().put("p_user_id", userId).toString(),
                accessToken,
            )
            if (result.first !in 200..299) return null
            val row = JSONArray(result.second).optJSONObject(0) ?: return null
            Profile(
                userId = row.optString("user_id", userId),
                displayName = row.optString("display_name", "قارئ السلايم").ifBlank { "قارئ السلايم" },
                avatarData = row.optString("avatar_data").takeIf { it.isNotBlank() && it != "null" },
                coverData = row.optString("cover_data").takeIf { it.isNotBlank() && it != "null" },
                level = row.optInt("level", 1).coerceAtLeast(1),
                rank = row.optString("rank", "F").ifBlank { "F" },
                followerCount = row.optInt("follower_count", 0).coerceAtLeast(0),
                followingCount = row.optInt("following_count", 0).coerceAtLeast(0),
                isFollowing = row.optBoolean("is_following", false),
            )
        }.getOrNull()
    }

    fun list(
        ctx: android.content.Context,
        userId: String,
        kind: ListKind,
        offset: Int,
        limit: Int = 50,
    ): List<User>? {
        return runCatching {
            val accessToken = token(ctx) ?: return null
            val result = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/${kind.rpc}",
                JSONObject()
                    .put("p_user_id", userId)
                    .put("p_limit", limit.coerceIn(1, 100))
                    .put("p_offset", offset.coerceAtLeast(0))
                    .toString(),
                accessToken,
            )
            if (result.first !in 200..299) return null
            val rows = JSONArray(result.second)
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                User(
                    userId = row.getString("user_id"),
                    displayName = row.optString("display_name", "قارئ السلايم").ifBlank { "قارئ السلايم" },
                    avatarData = row.optString("avatar_data").takeIf { it.isNotBlank() && it != "null" },
                    level = row.optInt("level", 1).coerceAtLeast(1),
                    rank = row.optString("rank", "F").ifBlank { "F" },
                )
            }
        }.getOrNull()
    }

    fun follow(ctx: android.content.Context, userId: String): String? = changeFollow(ctx, userId, "follow_user")

    fun unfollow(ctx: android.content.Context, userId: String): String? = changeFollow(ctx, userId, "unfollow_user")

    private fun changeFollow(ctx: android.content.Context, userId: String, rpc: String): String? {
        if (MslAuth.email(ctx).isBlank()) return "سجّل الدخول بحساب Google أولاً"
        return try {
            val accessToken = token(ctx) ?: return "تعذّر إنشاء جلسة الحساب"
            val result = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/$rpc",
                JSONObject().put("p_followed_id", userId).toString(),
                accessToken,
            )
            if (result.first in 200..299) {
                revision.value = revisionCounter.incrementAndGet()
                null
            } else {
                if (result.second.contains("Google sign-in required", ignoreCase = true)) {
                    "سجّل الدخول بحساب Google لتتمكن من المتابعة"
                } else {
                    "تعذّرت العملية (${result.first})"
                }
            }
        } catch (_: Exception) {
            "تعذّر الاتصال بالإنترنت"
        }
    }
}

class MslSocialProfileScreen(
    private val userId: String,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        MslThemed {
            MslSocialProfileContent(
                userId = userId,
                onOpenList = { kind -> navigator.push(MslSocialListScreen(userId, kind)) },
                onBack = navigator::pop,
            )
        }
    }
}

class MslSocialListScreen(
    private val userId: String,
    private val kind: MslFollowGraph.ListKind,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        MslThemed {
            MslSocialListContent(
                userId = userId,
                kind = kind,
                onOpenUser = { navigator.push(MslSocialProfileScreen(it)) },
                onBack = navigator::pop,
            )
        }
    }
}

@Composable
private fun MslSocialProfileContent(
    userId: String,
    onOpenList: (MslFollowGraph.ListKind) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var profile by remember(userId) { mutableStateOf<MslFollowGraph.Profile?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableIntStateOf(0) }
    val socialRevision by MslFollowGraph.revision.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(userId, refresh, socialRevision, resumed) {
        loading = true
        profile = withContext(Dispatchers.IO) { MslFollowGraph.profile(context, userId) }
        loading = false
    }

    val isMe = userId == MslSupabase.uid(context)
    val signedIn = MslAuth.email(context).isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MslDesignTokens.background)
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Text("‹", fontSize = 30.sp, color = MslDesignTokens.textPrimary) }
            Text("الملف الشخصي", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
        }
        if (loading && profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val user = profile
        if (user == null) {
            Text("تعذّر تحميل الملف الشخصي. تحقق من اتصالك ثم أعد المحاولة.", color = MslDesignTokens.textSecondary)
            Button(onClick = { refresh++ }) { Text("إعادة المحاولة") }
            return
        }
        MslSocialAvatar(user.avatarData, user.displayName, 100.dp)
        Spacer(Modifier.height(12.dp))
        Text(user.displayName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
        Text("المستوى ${user.level} • الرتبة ${user.rank}", color = MslDesignTokens.textSecondary)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MslSocialCount(
                count = user.followerCount,
                label = "المتابعون",
                modifier = Modifier.weight(1f),
                onClick = { onOpenList(MslFollowGraph.ListKind.FOLLOWERS) },
            )
            MslSocialCount(
                count = user.followingCount,
                label = "المتابَعون",
                modifier = Modifier.weight(1f),
                onClick = { onOpenList(MslFollowGraph.ListKind.FOLLOWING) },
            )
        }
        if (!isMe) {
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy,
                onClick = {
                    if (!signedIn) {
                        message = "سجّل الدخول بحساب Google لتتمكن من المتابعة"
                        MslAuth.startGoogle(context, true)
                    } else {
                        busy = true
                        scope.launch {
                            val error = withContext(Dispatchers.IO) {
                                if (user.isFollowing) {
                                    MslFollowGraph.unfollow(
                                        context,
                                        userId,
                                    )
                                } else {
                                    MslFollowGraph.follow(context, userId)
                                }
                            }
                            busy = false
                            message = error ?: ""
                            if (error == null) refresh++
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        when {
                            !signedIn -> "تسجيل الدخول للمتابعة"
                            user.isFollowing -> "إلغاء المتابعة"
                            else -> "متابعة"
                        },
                    )
                }
            }
            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
        }
        user.coverData?.let { cover ->
            Spacer(Modifier.height(20.dp))
            MslSocialImage(cover, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)))
        }
        Spacer(Modifier.height(16.dp))
        if (isMe) {
            Text(
                "هذا ملفك الشخصي العام. البريد الإلكتروني والمفضلة وبيانات الحساب الخاصة غير معروضة.",
                color = MslDesignTokens.textSecondary,
            )
        }
    }
}

@Composable
private fun MslSocialListContent(
    userId: String,
    kind: MslFollowGraph.ListKind,
    onOpenUser: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var users by remember(userId, kind) { mutableStateOf<List<MslFollowGraph.User>?>(null) }
    var loading by remember(userId, kind) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var offset by remember(userId, kind) { mutableIntStateOf(0) }
    var hasMore by remember(userId, kind) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(userId, kind, refresh) {
        loading = users == null
        loadingMore = users != null
        val page = withContext(Dispatchers.IO) { MslFollowGraph.list(context, userId, kind, offset) }
        if (page != null) {
            users = if (offset == 0) page else users.orEmpty() + page
            hasMore = page.size == 50
            offset += page.size
        }
        loading = false
        loadingMore = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MslDesignTokens.background)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Text("‹", fontSize = 30.sp, color = MslDesignTokens.textPrimary) }
            Text(kind.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            users == null -> Column {
                Text("تعذّر تحميل القائمة. تحقق من اتصالك.", color = MslDesignTokens.textSecondary)
                Button(onClick = { refresh++ }) { Text("إعادة المحاولة") }
            }
            users.isNullOrEmpty() -> Text("لا توجد حسابات في هذه القائمة بعد.", color = MslDesignTokens.textSecondary)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users.orEmpty(), key = { it.userId }) { user ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenUser(user.userId) },
                        shape = RoundedCornerShape(16.dp),
                        color = MslDesignTokens.surface,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MslSocialAvatar(user.avatarData, user.displayName, 48.dp)
                            Column {
                                Text(
                                    user.displayName,
                                    fontWeight = FontWeight.Bold,
                                    color = MslDesignTokens.textPrimary,
                                )
                                Text(
                                    "المستوى ${user.level} • ${user.rank}",
                                    color = MslDesignTokens.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (hasMore) {
                    item {
                        Button(
                            enabled = !loadingMore,
                            onClick = { refresh++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("تحميل المزيد")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MslSocialCount(
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MslDesignTokens.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MslDesignTokens.accentBright)
            Text(label, color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MslSocialAvatar(data: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    val color = Color(0xFF6452C8)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        var bitmap by remember(data) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(data) {
            bitmap = withContext(Dispatchers.IO) { decodeSocialImage(data) }
        }
        val image = bitmap
        if (image != null) {
            Image(
                image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(name.take(1).ifBlank { "؟" }, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun MslSocialImage(data: String, modifier: Modifier = Modifier) {
    var bitmap by remember(data) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(data) { bitmap = withContext(Dispatchers.IO) { decodeSocialImage(data) } }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
        ?: Box(modifier.background(MslDesignTokens.surface))
}

private fun decodeSocialImage(data: String?): Bitmap? {
    if (data.isNullOrBlank()) return null
    return runCatching {
        if (data.startsWith("data:image/")) {
            val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else if (data.startsWith("https://lh3.googleusercontent.com/")) {
            MslImages.load(data)
        } else {
            null
        }
    }.getOrNull()
}
