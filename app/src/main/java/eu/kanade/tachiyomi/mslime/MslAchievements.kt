package eu.kanade.tachiyomi.mslime

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object MslAchievements {
    private const val PREFS = "msl_achievement_cache"
    private const val USER_KEY = "user_id"
    private const val TITLE_ID_KEY = "equipped_title_id"
    private const val TITLE_LABEL_KEY = "equipped_title"
    private val _equippedTitleState = MutableStateFlow<String?>(null)
    val equippedTitleState = _equippedTitleState.asStateFlow()

    data class Achievement(
        val id: String,
        val name: String,
        val description: String,
        val progress: Int,
        val target: Int,
        val claimed: Boolean,
        val title: String,
        val equipped: Boolean,
    ) {
        val claimable: Boolean get() = !claimed && progress >= target
    }

    data class Snapshot(
        val achievements: List<Achievement>,
        val equippedTitleId: String?,
        val equippedTitle: String?,
    )

    fun cachedTitle(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = if (prefs.getString(USER_KEY, null) != MslSupabase.uid(context)) {
            null
        } else {
            prefs.getString(TITLE_LABEL_KEY, null)
        }
        _equippedTitleState.value = title
        return title
    }

    fun refresh(context: Context): Snapshot? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call("POST", "/rest/v1/rpc/get_achievements", "{}", token)
        if (response.first !in 200..299) return null
        val root = JSONObject(response.second)
        val rows = root.optJSONArray("achievements") ?: JSONArray()
        val achievements = List(rows.length()) { index ->
            val item = rows.getJSONObject(index)
            Achievement(
                id = item.optString("id"),
                name = item.optString("name"),
                description = item.optString("description"),
                progress = item.optInt("progress").coerceAtLeast(0),
                target = item.optInt("target").coerceAtLeast(1),
                claimed = item.optBoolean("claimed"),
                title = item.optString("title"),
                equipped = item.optBoolean("equipped"),
            )
        }
        val titleId = root.optString("equipped_title_id").takeIf { it.isNotBlank() && it != "null" }
        val title = root.optString("equipped_title").takeIf { it.isNotBlank() && it != "null" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(USER_KEY, MslSupabase.uid(context))
            .putString(TITLE_ID_KEY, titleId)
            .putString(TITLE_LABEL_KEY, title)
            .apply()
        _equippedTitleState.value = title
        Snapshot(achievements, titleId, title)
    }.getOrNull()

    fun claim(context: Context, achievementId: String): Boolean = runCatching {
        val token = MslSupabase.token(context) ?: return false
        MslSupabase.call(
            "POST",
            "/rest/v1/rpc/claim_achievement",
            JSONObject().put("p_achievement_id", achievementId).toString(),
            token,
        ).first in 200..299
    }.getOrDefault(false)

    fun equip(context: Context, achievementId: String): Boolean = runCatching {
        val token = MslSupabase.token(context) ?: return false
        MslSupabase.call(
            "POST",
            "/rest/v1/rpc/equip_achievement_title",
            JSONObject().put("p_achievement_id", achievementId).toString(),
            token,
        ).first in 200..299
    }.getOrDefault(false)
}

class MslAchievementsScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf<MslAchievements.Snapshot?>(null) }
        var loading by remember { mutableStateOf(true) }
        var busyId by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }

        suspend fun reload() {
            snapshot = withContext(Dispatchers.IO) { MslAchievements.refresh(context) }
        }
        LaunchedEffect(Unit) {
            reload()
            loading = false
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("الإنجازات والألقاب") },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = { navigator.pop() }) {
                            Text("‹", style = MaterialTheme.typography.headlineMedium)
                        }
                    },
                )
            },
        ) { padding ->
            when {
                loading -> androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                snapshot == null -> androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("تعذّر تحميل الإنجازات. تحقق من الاتصال ثم أعد المحاولة.") }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        val currentTitle = snapshot?.equippedTitle
                        Text(
                            currentTitle?.let { "اللقب المجهز: $it" } ?: "لا يوجد لقب مجهز حاليًا.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!message.isNullOrBlank()) Text(message.orEmpty(), color = MaterialTheme.colorScheme.secondary)
                    }
                    items(snapshot?.achievements.orEmpty(), key = { it.id }) { achievement ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(achievement.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(achievement.description, style = MaterialTheme.typography.bodyMedium)
                                Text("التقدم: ${achievement.progress.coerceAtMost(achievement.target)} / ${achievement.target}")
                                Text("اللقب: ${achievement.title}", color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (achievement.claimable) {
                                        Button(
                                            enabled = busyId == null,
                                            onClick = {
                                                busyId = achievement.id
                                                scope.launch {
                                                    val claimed = withContext(Dispatchers.IO) {
                                                        MslAchievements.claim(context, achievement.id)
                                                    }
                                                    reload()
                                                    message = if (claimed) "تمت المطالبة بالإنجاز وإضافة اللقب إلى مجموعتك." else "لم تكتمل المطالبة؛ تحقق من اتصال الإنترنت والتقدم."
                                                    busyId = null
                                                }
                                            },
                                        ) { Text(if (busyId == achievement.id) "جارٍ الحفظ…" else "المطالبة بالإنجاز") }
                                    }
                                    if (achievement.claimed) {
                                        OutlinedButton(
                                            enabled = busyId == null && !achievement.equipped,
                                            onClick = {
                                                busyId = achievement.id
                                                scope.launch {
                                                    val equipped = withContext(Dispatchers.IO) {
                                                        MslAchievements.equip(context, achievement.id)
                                                    }
                                                    reload()
                                                    message = if (equipped) "تم تجهيز اللقب في ملفك الشخصي." else "تعذّر تجهيز اللقب."
                                                    busyId = null
                                                }
                                            },
                                        ) { Text(if (achievement.equipped) "مجهز" else "تجهيز اللقب") }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
