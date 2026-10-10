#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"
STORE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelFavoritesStore.kt"

echo "⭐ Adding novel favorites..."

mkdir -p "$(dirname "$STORE")"

cat > "$STORE" <<'KOTLIN'
package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NovelFavoriteEntry(
    val sourceId: String,
    val novelPath: String,
    val title: String,
    val cover: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

class NovelFavoritesStore(context: Context) {

    private val file =
        File(context.applicationContext.filesDir, "msl_novel_favorites.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun all(): List<NovelFavoriteEntry> {
        if (!file.exists()) return emptyList()

        return runCatching {
            json.decodeFromString<List<NovelFavoriteEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun contains(sourceId: String, novelPath: String): Boolean {
        return all().any {
            it.sourceId == sourceId && it.novelPath == novelPath
        }
    }

    @Synchronized
    fun toggle(entry: NovelFavoriteEntry): Boolean {
        val current = all().toMutableList()

        val index = current.indexOfFirst {
            it.sourceId == entry.sourceId &&
                it.novelPath == entry.novelPath
        }

        return if (index >= 0) {
            current.removeAt(index)
            file.writeText(json.encodeToString(current))
            false
        } else {
            current.add(
                0,
                entry.copy(
                    addedAt = System.currentTimeMillis(),
                ),
            )
            file.writeText(json.encodeToString(current.take(MAX_ENTRIES)))
            true
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
KOTLIN

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt")
s = p.read_text()

# ------------------------------------------------------------
# 1. Add favorite state
# ------------------------------------------------------------

old = """    val manager = remember { NovelManagerHolder.get(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NovelUiState()) }
"""

new = """    val manager = remember { NovelManagerHolder.get(context) }
    val favoritesStore = remember { NovelFavoritesStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NovelUiState()) }
    var favoritesRevision by remember { mutableIntStateOf(0) }
    val favoriteEntries = remember(favoritesRevision) {
        favoritesStore.all()
    }
    val favoriteKeys = remember(favoriteEntries) {
        favoriteEntries.map { "${it.sourceId}::${it.novelPath}" }.toSet()
    }
"""

if old in s and "favoritesStore = remember" not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 2. Add favorite filter state to NovelUiState
# ------------------------------------------------------------

old = """    val inputMode: NovelInputMode = NovelInputMode.REPOSITORY,
    val error: String? = null,
    val info: String? = null,
)
"""

new = """    val inputMode: NovelInputMode = NovelInputMode.REPOSITORY,
    val showFavorites: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)
"""

if old in s and "val showFavorites: Boolean" not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 3. Pass favorite information into NovelShell
# ------------------------------------------------------------

old = """        state = state,
        onSearch = { q ->
"""

new = """        state = state,
        favoriteKeys = favoriteKeys,
        onToggleFavorite = { item ->
            val sid = state.selectedSource
            if (sid != null) {
                favoritesStore.toggle(
                    NovelFavoriteEntry(
                        sourceId = sid,
                        novelPath = item.path,
                        title = item.name,
                        cover = item.cover,
                    ),
                )
                favoritesRevision++
            }
        },
        onToggleFavoriteFilter = {
            state = state.copy(showFavorites = !state.showFavorites)
        },
        onSearch = { q ->
"""

if old in s and "favoriteKeys = favoriteKeys" not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 4. Add parameters to NovelShell
# ------------------------------------------------------------

old = """private fun NovelShell(
    state: NovelUiState,
    onSearch: (String) -> Unit,
"""

new = """private fun NovelShell(
    state: NovelUiState,
    favoriteKeys: Set<String>,
    onToggleFavorite: (NovelItem) -> Unit,
    onToggleFavoriteFilter: () -> Unit,
    onSearch: (String) -> Unit,
"""

if old in s and "favoriteKeys: Set<String>" not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 5. Add favorites chip
# ------------------------------------------------------------

old = """                item {
                    AssistChip(onClick = onAddRepository, label = {
                        Text("إضافة مصدر")
                    })
                }
"""

new = """                item {
                    AssistChip(onClick = onAddRepository, label = {
                        Text("إضافة مصدر")
                    })
                }

                item {
                    FilterChip(
                        selected = state.showFavorites,
                        onClick = onToggleFavoriteFilter,
                        label = {
                            Text("⭐ المفضلة")
                        },
                    )
                }
"""

if old in s and 'Text("⭐ المفضلة")' not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 6. Filter novels + update card
# ------------------------------------------------------------

old = """                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.novels) { item -> NovelCard(item, onOpen) }
                }
"""

new = """                else -> {
                    val visibleNovels =
                        if (state.showFavorites) {
                            state.novels.filter {
                                state.selectedSource != null &&
                                    "${state.selectedSource}::${it.path}" in favoriteKeys
                            }
                        } else {
                            state.novels
                        }

                    if (state.showFavorites && visibleNovels.isEmpty()) {
                        EmptyFavorites()
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(145.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visibleNovels) { item ->
                                NovelCard(
                                    item = item,
                                    onOpen = onOpen,
                                    isFavorite = state.selectedSource != null &&
                                        "${state.selectedSource}::${item.path}" in favoriteKeys,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    }
                }
"""

if old in s and "val visibleNovels" not in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 7. Replace NovelCard
# ------------------------------------------------------------

start = s.find("@Composable\nprivate fun NovelCard(")
end = s.find("\nclass NovelDetailsScreen(", start)

if start != -1 and end != -1:
    card = r'''@Composable
private fun NovelCard(
    item: NovelItem,
    onOpen: (NovelItem) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (NovelItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box {
            Column(
                modifier = Modifier.clickable { onOpen(item) },
            ) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                            ),
                        ),
                )

                Text(
                    item.name,
                    modifier = Modifier.padding(10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
            }

            FilledTonalIconButton(
                onClick = { onToggleFavorite(item) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text(
                    text = if (isFavorite) "★" else "☆",
                    fontSize = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyFavorites() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "⭐ لا توجد روايات مفضلة",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "أضف الروايات التي تريد الرجوع إليها لاحقًا.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
'''
    s = s[:start] + card + s[end:]

# ------------------------------------------------------------
# 8. Add favorite button to details
# ------------------------------------------------------------

old = """        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
"""

new = """        val favoritesStore = remember { NovelFavoritesStore(context) }
        var favoriteRevision by remember { mutableIntStateOf(0) }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
"""

if old in s and "favoriteRevision" not in s:
    s = s.replace(old, new, 1)

old = """        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(details?.name ?: fallbackName)
                }, navigationIcon = { IconButton(onClick = { navigator.pop() }) { Text("‹", fontSize = 32.sp) } })
            },
        ) { padding ->
"""

new = """        val isFavorite = remember(favoriteRevision, details) {
            favoritesStore.contains(sourceId, path)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(details?.name ?: fallbackName)
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Text("‹", fontSize = 32.sp)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val d = details
                                favoritesStore.toggle(
                                    NovelFavoriteEntry(
                                        sourceId = sourceId,
                                        novelPath = path,
                                        title = d?.name ?: fallbackName,
                                        cover = d?.cover,
                                    ),
                                )
                                favoriteRevision++
                            },
                        ) {
                            Text(
                                if (isFavorite) "★" else "☆",
                                fontSize = 26.sp,
                            )
                        }
                    },
                )
            },
        ) { padding ->
"""

if old in s and 'val isFavorite = remember(favoriteRevision, details)' not in s:
    s = s.replace(old, new, 1)

p.write_text(s)
PY

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== FAVORITES CHECK ====="
grep -n "NovelFavoritesStore\|NovelFavoriteEntry\|favoriteKeys\|showFavorites\|المفضلة\|EmptyFavorites" "$FILE" "$STORE" | head -80

echo
echo "===== STATUS ====="
git status --short

echo
echo "✅ Novel favorites patch applied."
echo
echo "Next validation:"
echo "./gradlew :app:compileDebugKotlin --no-daemon"
