@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.mslime

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.novel.NovelFavoriteEntry
import eu.kanade.tachiyomi.novel.NovelFavoritesStore
import mihon.app.di.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Favorite categories stored in the existing RLS-protected user_favorites table. */
enum class MslFavoriteKind(val label: String, val wireType: String) {
    MANGA("المانغا المفضلة", "manga"),
    NOVEL("الروايات المفضلة", "novel"),
    COMMUNITY("روايات المجتمع المفضلة", "community_novel"),
}

data class MslFavoriteItem(
    val kind: MslFavoriteKind,
    val itemKey: String,
    val title: String,
    val cover: String? = null,
    val sourceId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

data class MslFavoriteSnapshot(
    val items: List<MslFavoriteItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loginRequired: Boolean = false,
    val error: String? = null,
)

/** Session-memory cache; Supabase is the source of truth and no new local favorite database is created. */
object MslFavorites {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(MslFavoriteSnapshot())
    val snapshot: StateFlow<MslFavoriteSnapshot> = mutableSnapshot.asStateFlow()

    fun count(kind: MslFavoriteKind): Int = mutableSnapshot.value.items.count { it.kind == kind }

    suspend fun load(context: Context, force: Boolean = false) {
        if (!force && (mutableSnapshot.value.loaded || mutableSnapshot.value.loading)) return
        mutex.withLock {
            if (!force && mutableSnapshot.value.loaded) return
            mutableSnapshot.value = mutableSnapshot.value.copy(loading = true, error = null, loginRequired = false)
            withContext(Dispatchers.IO) {
                try {
                    if (MslAuth.email(context).isBlank()) {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            loading = false,
                            loaded = true,
                            loginRequired = true,
                        )
                        return@withContext
                    }
                    val token = MslSupabase.token(context)
                        ?: error("تعذّر إنشاء جلسة Supabase. سجّل الدخول ثم أعد المحاولة.")
                    val uid = MslSupabase.uid(context).takeIf(String::isNotBlank)
                        ?: error("تعذّر تحديد المستخدم الحالي. سجّل الدخول ثم أعد المحاولة.")
                    val rows = requestRows(
                        token,
                        "/rest/v1/user_favorites?select=item_type,item_key,title,cover,source_id,added_at&user_id=eq.$uid&order=added_at.desc&limit=5000",
                    )
                    val items = buildList {
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            val kind = when (row.optString("item_type")) {
                                "manga" -> MslFavoriteKind.MANGA
                                "novel" -> MslFavoriteKind.NOVEL
                                "community_novel" -> MslFavoriteKind.COMMUNITY
                                else -> null
                            } ?: continue
                            add(
                                MslFavoriteItem(
                                    kind = kind,
                                    itemKey = row.optString("item_key"),
                                    title = row.optString("title").ifBlank { "عمل محفوظ" },
                                    cover = row.optString("cover").takeIf(String::isNotBlank),
                                    sourceId = row.optString("source_id").takeIf(String::isNotBlank),
                                    addedAt = row.optLong("added_at", System.currentTimeMillis()),
                                ),
                            )
                        }
                    }.toMutableList()
                    items += loadCommunityLibrary(token, uid)
                    mutableSnapshot.value = MslFavoriteSnapshot(items = items.distinctBy { it.kind to it.itemKey }, loaded = true)
                    // Keep the existing on-device novel list as a compatibility cache only; remote rows remain authoritative.
                    val remoteNovels = items.filter { it.kind == MslFavoriteKind.NOVEL }.mapNotNull { item ->
                        val parts = item.itemKey.split("::", limit = 2)
                        if (parts.size != 2) return@mapNotNull null
                        NovelFavoriteEntry(parts[0], parts[1], item.title, item.cover, item.addedAt)
                    }
                    if (remoteNovels.isNotEmpty()) NovelFavoritesStore(context).replace(remoteNovels)
                } catch (failure: Exception) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        loading = false,
                        loaded = true,
                        loginRequired = false,
                        error = failure.message ?: "تعذّر تحميل المفضلة. تحقق من الاتصال ثم أعد المحاولة.",
                    )
                }
            }
        }
    }

    /** Returns null on success, otherwise an Arabic error. UI state changes immediately and rolls back on failure. */
    suspend fun toggle(context: Context, item: MslFavoriteItem): String? {
        val before = mutableSnapshot.value
        if (!before.loaded) load(context)
        val current = mutableSnapshot.value
        if (current.loginRequired || MslAuth.email(context).isBlank()) return "سجّل الدخول أولًا لإدارة المفضلة."
        if (current.error != null) return current.error
        val exists = current.items.any { it.kind == item.kind && it.itemKey == item.itemKey }
        val desired = !exists
        val optimistic = if (desired) item.copy(addedAt = System.currentTimeMillis()) else item
        mutableSnapshot.value = current.copy(
            items = if (desired) listOf(optimistic) + current.items else current.items.filterNot { it.kind == item.kind && it.itemKey == item.itemKey },
            error = null,
        )

        return withContext(Dispatchers.IO) {
            try {
                val token = MslSupabase.token(context) ?: error("انتهت جلسة الدخول. سجّل الدخول ثم أعد المحاولة.")
                val uid = MslSupabase.uid(context).takeIf(String::isNotBlank) ?: error("تعذّر تحديد المستخدم الحالي.")
                val rowSuccess = writeFavoriteRow(token, uid, item, item.kind.wireType, desired, optimistic.addedAt)
                val success = if (!rowSuccess) {
                    false
                } else if (item.kind == MslFavoriteKind.COMMUNITY) {
                    val librarySuccess = CommunityNovelApi.toggleLibrary(context, item.itemKey, desired)
                    if (!librarySuccess) {
                        // Keep the existing community library behavior in sync; compensate the new row on failure.
                        runCatching { writeFavoriteRow(token, uid, item, item.kind.wireType, !desired, item.addedAt) }
                    }
                    librarySuccess
                } else {
                    true
                }
                if (!success) error("لم يتم حفظ التغيير في Supabase. حاول مجددًا.")
                if (item.kind == MslFavoriteKind.MANGA && !desired) {
                    runCatching {
                        val localFavorites = context.appGraph.getFavorites.await()
                        for (manga in localFavorites.filter { it.url == item.itemKey }) {
                            context.appGraph.updateManga.awaitUpdateFavorite(manga.id, false)
                        }
                    }
                }
                if (item.kind == MslFavoriteKind.NOVEL) {
                    val now = mutableSnapshot.value.items.filter { it.kind == MslFavoriteKind.NOVEL }
                    val mirror = now.mapNotNull { row ->
                        val parts = row.itemKey.split("::", limit = 2)
                        if (parts.size != 2) null else NovelFavoriteEntry(parts[0], parts[1], row.title, row.cover, row.addedAt)
                    }
                    NovelFavoritesStore(context).replace(mirror)
                }
                null
            } catch (failure: Exception) {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    items = current.items,
                    error = failure.message ?: "تعذّر حفظ المفضلة.",
                )
                failure.message ?: "تعذّر حفظ المفضلة."
            }
        }
    }

    private fun requestRows(token: String, path: String): JSONArray {
        val response = MslSupabase.call("GET", path, null, token)
        if (response.first !in 200..299) error("فشل تحميل المفضلة (${response.first}).")
        return JSONArray(response.second)
    }

    private fun writeFavoriteRow(token: String, uid: String, item: MslFavoriteItem, itemType: String, desired: Boolean, addedAt: Long): Boolean {
        return if (desired) {
            val body = JSONObject()
                .put("user_id", uid)
                .put("item_type", itemType)
                .put("item_key", item.itemKey)
                .put("title", item.title)
                .put("cover", item.cover ?: JSONObject.NULL)
                .put("source_id", item.sourceId ?: JSONObject.NULL)
                .put("added_at", addedAt)
            MslSupabase.call("POST", "/rest/v1/user_favorites", body.toString(), token, "resolution=ignore-duplicates,return=minimal").first in 200..299
        } else {
            val key = java.net.URLEncoder.encode(item.itemKey, "UTF-8")
            MslSupabase.call(
                "DELETE",
                "/rest/v1/user_favorites?user_id=eq.$uid&item_type=eq.$itemType&item_key=eq.$key",
                null,
                token,
            ).first in 200..299
        }
    }

    private fun loadCommunityLibrary(token: String, uid: String): List<MslFavoriteItem> {
        val saved = requestRows(
            token,
            "/rest/v1/community_novel_library?select=novel_id&user_id=eq.$uid&limit=5000",
        )
        if (saved.length() == 0) return emptyList()
        val ids = buildList { for (i in 0 until saved.length()) add(saved.getJSONObject(i).optString("novel_id")) }
            .filter(String::isNotBlank)
            .distinct()
        if (ids.isEmpty()) return emptyList()
        val novels = requestRows(
            token,
            "/rest/v1/community_novels?select=id,title,cover_path&id=in.(${ids.joinToString(",")})&limit=5000",
        )
        return List(novels.length()) { i ->
            val row = novels.getJSONObject(i)
            MslFavoriteItem(
                kind = MslFavoriteKind.COMMUNITY,
                itemKey = row.optString("id"),
                title = row.optString("title").ifBlank { "رواية مجتمع" },
                cover = row.optString("cover_path").takeIf(String::isNotBlank),
                sourceId = "community",
            )
        }
    }
}

@Composable
fun MslFavoriteListScreen(kind: MslFavoriteKind) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by MslFavorites.snapshot.collectAsState()
    LaunchedEffect(Unit) {
        if (!state.loaded && !state.loading) MslFavorites.load(context)
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && MslFavorites.snapshot.value.loginRequired) {
                scope.launch { MslFavorites.load(context, force = true) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MslDesignTokens.background)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(kind.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MslDesignTokens.textPrimary)
        when {
            state.loading && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MslDesignTokens.accentBright)
            }
            state.loginRequired -> FavoriteMessage(
                message = "يلزم تسجيل الدخول لعرض المفضلة ومزامنتها مع حسابك.",
                action = "تسجيل الدخول",
                onAction = { MslAuth.startGoogle(context, link = true) },
            )
            state.error != null -> FavoriteMessage(
                message = state.error ?: "تعذّر تحميل المفضلة.",
                action = "إعادة المحاولة",
                onAction = { scope.launch { MslFavorites.load(context, force = true) } },
            )
            state.items.none { it.kind == kind } -> Text(
                "لا توجد أعمال في هذه القائمة بعد",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 54.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MslDesignTokens.surface)
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                color = MslDesignTokens.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items.filter { it.kind == kind }, key = { "${it.kind.name}:${it.itemKey}" }) { item ->
                    FavoriteRow(item = item, onRemove = {
                        scope.launch {
                            val error = MslFavorites.toggle(context, item)
                            if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun FavoriteMessage(message: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text(action) }
    }
}

@Composable
private fun FavoriteRow(item: MslFavoriteItem, onRemove: () -> Unit) {
    Card(
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!item.cover.isNullOrBlank()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = null,
                    modifier = Modifier.size(width = 58.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size(width = 58.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)).background(MslDesignTokens.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", color = MslDesignTokens.accentBright, fontSize = 22.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.kind.label.removeSuffix(" المفضلة"), color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "إزالة ${item.title} من المفضلة" },
            ) {
                Text("×", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
