package eu.kanade.tachiyomi.mslime

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class DiscoveredUser(
    val id: String,
    val name: String,
    val avatar: String?,
    val level: Int,
    val rank: String,
    val followerCount: Int?,
    val email: String?,
    val isFollowing: Boolean,
)

private object MslDiscoveryApi {
    fun search(context: Context, query: String, offset: Int): List<DiscoveredUser> {
        val token = MslSupabase.token(context) ?: return emptyList()
        val result = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/search_public_users",
            JSONObject().put("p_query", query).put("p_limit", 20).put("p_offset", offset).toString(),
            token,
        )
        check(result.first in 200..299) { "تعذّر البحث (${result.first})" }
        val rows = JSONArray(result.second)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            DiscoveredUser(
                id = row.getString("user_id"),
                name = row.optString("display_name", "قارئ السلايم"),
                avatar = row.optString("avatar_data").takeUnless { it.isBlank() || it == "null" },
                level = row.optInt("level", 1),
                rank = row.optString("rank", "F"),
                followerCount = if (row.isNull("follower_count")) null else row.optInt("follower_count"),
                email = row.optString("email").takeUnless { it.isBlank() || it == "null" },
                isFollowing = row.optBoolean("is_following"),
            )
        }
    }

    fun getPrivacy(context: Context): Map<String, String> {
        val token = MslSupabase.token(context) ?: error("سجّل الدخول أولاً")
        val response = MslSupabase.call("POST", "/rest/v1/rpc/get_my_privacy_settings", "{}", token)
        check(response.first in 200..299) { "تعذّر تحميل الإعدادات (${response.first})" }
        val json = runCatching { JSONObject(response.second) }.getOrElse {
            JSONArray(response.second).optJSONObject(0) ?: JSONObject()
        }
        return privacyKeys.associateWith { key -> json.optString(key, privacyDefaults.getValue(key)) }
    }

    fun savePrivacy(context: Context, values: Map<String, String>): Boolean {
        val token = MslSupabase.token(context) ?: return false
        val settings = JSONObject()
        values.forEach { (key, value) -> settings.put(key, value) }
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/update_my_privacy_settings",
            JSONObject().put("p_settings", settings).toString(),
            token,
        )
        return response.first in 200..299 && runCatching { JSONObject(response.second).optBoolean("ok") }.getOrDefault(false)
    }
}

private val privacyLabels = linkedMapOf(
    "profile_visibility" to "الملف الأساسي والمستوى والرتبة",
    "cover_visibility" to "صورة الغلاف",
    "favorites_visibility" to "مانهواي المفضلة",
    "novel_favorites_visibility" to "رواياتي المفضلة",
    "sources_visibility" to "المصادر",
    "reading_stats_visibility" to "إحصائيات القراءة",
    "followers_visibility" to "قائمة المتابعين وعددهم",
    "following_visibility" to "قائمة المتابَعين وعددهم",
    "comments_visibility" to "التعليقات العامة",
    "email_visibility" to "البريد الإلكتروني والبحث به",
    "joined_at_visibility" to "تاريخ الانضمام",
    "equipped_frame_visibility" to "إطار الصورة الشخصية",
)
private val privacyKeys = privacyLabels.keys.toList()
private val privacyDefaults = privacyKeys.associateWith { key ->
    when (key) {
        "profile_visibility", "cover_visibility", "followers_visibility", "following_visibility", "comments_visibility", "joined_at_visibility", "equipped_frame_visibility" -> "public"
        else -> "private"
    }
}

class MslUserSearchScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        MslThemed {
            MslUserSearchContent(onBack = navigator::pop, onOpen = { navigator.push(MslSocialProfileScreen(it)) })
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MslUserSearchContent(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DiscoveredUser>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    val followStates = remember { mutableStateMapOf<String, Boolean>() }
    var followBusy by remember { mutableStateOf<Set<String>>(emptySet()) }
    val settledQuery = query.trim()
    LaunchedEffect(settledQuery, retryTick) {
        results = emptyList(); error = null; searched = false; hasMore = false
        if (settledQuery.length < 2) { loading = false; return@LaunchedEffect }
        delay(350)
        loading = true
        try {
            val page = withContext(Dispatchers.IO) { MslDiscoveryApi.search(context, settledQuery, 0) }
            results = page
            hasMore = page.size == 20
            searched = true
        } catch (e: Exception) {
            error = e.message ?: "تعذّر البحث. حاول مرة أخرى."
        } finally { loading = false }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("البحث عن المستخدمين", fontWeight = FontWeight.Bold) }, navigationIcon = {
                IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("الاسم أو البريد العام") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            when {
                settledQuery.isBlank() -> Text("أدخل اسماً أو بريداً عاماً للبحث.", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                settledQuery.length == 1 -> Text("اكتب حرفين على الأقل.", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                loading && results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && results.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retryTick++ }) { Text("إعادة المحاولة") }
                }
                searched && results.isEmpty() -> Text("لا توجد نتائج.", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    items(results, key = { it.id }) { user ->
                        val isSelf = user.id == MslSupabase.uid(context)
                        Surface(shape = RoundedCornerShape(16.dp), color = MslDesignTokens.surface, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DiscoveryAvatar(user.avatar, user.name)
                                Column(Modifier.weight(1f).clickable { onOpen(user.id) }) {
                                    Text(user.name, fontWeight = FontWeight.Bold)
                                    Text("المستوى ${user.level} • الرتبة ${user.rank}" + (user.followerCount?.let { " • $it متابع" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                    user.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (!isSelf && MslAuth.email(context).isNotBlank()) {
                                    val isFollowing = followStates[user.id] ?: user.isFollowing
                                    Button(enabled = user.id !in followBusy, onClick = {
                                        followBusy = followBusy + user.id
                                        scope.launch {
                                            val failure = withContext(Dispatchers.IO) {
                                                if (isFollowing) MslFollowGraph.unfollow(context, user.id)
                                                else MslFollowGraph.follow(context, user.id)
                                            }
                                            followBusy = followBusy - user.id
                                            if (failure == null) followStates[user.id] = !isFollowing
                                        }
                                    }) { Text(if (isFollowing) "إلغاء" else "متابعة") }
                                }
                            }
                        }
                    }
                    if (hasMore) item {
                        Button(enabled = !loadingMore, modifier = Modifier.fillMaxWidth(), onClick = {
                            loadingMore = true
                            scope.launch {
                                try {
                                    val next = withContext(Dispatchers.IO) { MslDiscoveryApi.search(context, settledQuery, results.size) }
                                    results = results + next
                                    hasMore = next.size == 20
                                } catch (e: Exception) { error = e.message ?: "تعذّر تحميل المزيد." }
                                finally { loadingMore = false }
                            }
                        }) { if (loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("تحميل المزيد") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryAvatar(data: String?, name: String) {
    MslSocialAvatar(data, name, 48.dp)
}

class MslPrivacySettingsScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        MslThemed { MslPrivacySettingsContent(onBack = navigator::pop) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MslPrivacySettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { values.putAll(withContext(Dispatchers.IO) { MslDiscoveryApi.getPrivacy(context) }) }
        catch (e: Exception) { message = e.message ?: "تعذّر تحميل إعدادات الخصوصية." }
        finally { loading = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("إعدادات الخصوصية", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
        })
    }) { padding ->
        if (loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Column(Modifier.fillMaxSize().padding(padding)) {
            Text("اختر من يمكنه رؤية كل نوع من بيانات ملفك العام.", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MslDesignTokens.textSecondary)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(privacyKeys) { key ->
                    var expanded by remember(key) { mutableStateOf(false) }
                    Surface(color = MslDesignTokens.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(privacyLabels.getValue(key), fontWeight = FontWeight.Medium)
                            Box {
                                Button(onClick = { expanded = true }) { Text(visibilityLabel(values[key] ?: privacyDefaults.getValue(key))) }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    listOf("public", "followers", "private").forEach { visibility ->
                                        DropdownMenuItem(text = { Text(visibilityLabel(visibility)) }, onClick = { values[key] = visibility; expanded = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { MslDiscoveryApi.savePrivacy(context, values.toMap()) }
                        busy = false
                        message = if (saved) "تم حفظ إعدادات الخصوصية." else "تعذّر حفظ الإعدادات. تحقق من تسجيل الدخول."
                    }
                },
            ) { Text(if (busy) "جارٍ الحفظ…" else "حفظ الإعدادات") }
        }
    }
}

private fun visibilityLabel(value: String): String = when (value) {
    "public" -> "الجميع"
    "followers" -> "المتابعون فقط"
    else -> "أنا فقط"
}
