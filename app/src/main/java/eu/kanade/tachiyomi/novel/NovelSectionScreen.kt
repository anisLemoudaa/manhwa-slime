@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.GestureDetector
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Person
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.mslime.MslAds
import eu.kanade.tachiyomi.mslime.MslAdultContentFilter
import eu.kanade.tachiyomi.mslime.MslChapterAccess
import eu.kanade.tachiyomi.mslime.MslCommentsDialog
import eu.kanade.tachiyomi.mslime.MslFavoriteItem
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavorites
import eu.kanade.tachiyomi.mslime.MslTranslate
import eu.kanade.tachiyomi.mslime.MslWallet
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

private fun WebView.scrollableRange(): Int =
    (contentHeight * scale - height).toInt().coerceAtLeast(1)

@Composable
private fun NovelToolbar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.widthIn(min = 56.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                navigationIcon()
            }
            Box(
                modifier = Modifier.weight(1f),
            ) {
                title()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

private data class NovelUiState(
    val sources: List<NovelSourceRuntime> = emptyList(),
    val selectedSource: String? = null,
    val allSources: Boolean = false,
    val novels: List<NovelItem> = emptyList(),
    val latestNovel: NovelItem? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val page: Int = 1,
    val query: String = "",
    val sortMode: String = "new",
    val layoutMode: String = "grid",
    val repoDialog: Boolean = false,
    val sourcesDialog: Boolean = false,
    val repoUrl: String = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
    val repoEntries: List<NovelRepositoryEntry> = emptyList(),
    val savedRepositories: List<String> = emptyList(),
    val sourceLanguageFilter: String = "supported",
    val catalogLoading: Boolean = false,
    val installingId: String? = null,
    val inputMode: NovelInputMode = NovelInputMode.REPOSITORY,
    val showFavorites: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

private enum class NovelInputMode { REPOSITORY, DIRECT_URL }

private fun isValidNovelHttpUrl(value: String): Boolean = runCatching {
    val parsed = java.net.URI(value.trim())
    parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()
}.getOrDefault(false)

class NovelSectionScreen : Screen() {
    @Composable
    override fun Content() {
        NovelSectionContent()
    }
}

@Composable
fun NovelSectionContent() {
    NovelTheme { NovelSectionContentBody() }
}

@Composable
private fun NovelSectionContentBody() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val manager = remember { NovelManagerHolder.get(context) }
    val uiPrefs = remember { context.getSharedPreferences("msl_novel_ui", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf(
            NovelUiState(
                selectedSource = uiPrefs.getString("last_source", null),
                allSources = uiPrefs.getBoolean("all_sources", false),
                sortMode = uiPrefs.getString("sort_mode", "new") ?: "new",
                layoutMode = uiPrefs.getString("layout_mode", "grid") ?: "grid",
            ),
        )
    }
    var pluginsInitialized by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val favoriteSnapshot by MslFavorites.snapshot.collectAsState()
    val favoriteKeys = remember(favoriteSnapshot.items) {
        favoriteSnapshot.items.filter { it.kind == MslFavoriteKind.NOVEL }.map { it.itemKey }.toSet()
    }

    LaunchedEffect(Unit) {
        if (!favoriteSnapshot.loaded && !favoriteSnapshot.loading) MslFavorites.load(context)
    }

    fun reload(sourceId: String? = state.selectedSource, allSources: Boolean = state.allSources) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val querySnapshot = state.query.trim()
            if (querySnapshot.isNotEmpty()) delay(400)
            val sources = runCatching {
                if (!pluginsInitialized) {
                    manager.ensureLoaded()
                    pluginsInitialized = true
                }
                manager.installed().filter { it.languageCode in setOf("ar", "en") }
            }.getOrDefault(emptyList())

            val sid = if (allSources) {
                null
            } else {
                sourceId?.takeIf { requestedId -> sources.any { it.id == requestedId } }
                    ?: uiPrefs.getString("last_source", null)?.takeIf { saved -> sources.any { it.id == saved } }
                    ?: sources.firstOrNull()?.id
            }
            state = state.copy(
                sources = sources,
                selectedSource = sid,
                allSources = allSources,
                loading = true,
                loadingMore = false,
                page = 1,
                hasMore = true,
                latestNovel = null,
                error = null,
                info = null,
            )

            val targets = if (allSources) sources else sources.filter { it.id == sid }
            if (targets.isEmpty()) {
                state = state.copy(novels = emptyList(), latestNovel = null, loading = false, hasMore = false)
                return@launch
            }

            if (sid != null) uiPrefs.edit().putString("last_source", sid).apply()
            uiPrefs.edit().putBoolean("all_sources", allSources).apply()
            val sortMode = state.sortMode
            val latestDeferred = if (!allSources && sid != null && querySnapshot.isBlank() && sortMode != "new") {
                async(Dispatchers.IO) {
                    runCatching {
                        manager.latest(sid).firstOrNull()?.copy(
                            sourceId = sid,
                            sourceName = sources.firstOrNull {
                                it.id ==
                                    sid
                            }?.name,
                        )
                    }
                }
            } else {
                null
            }
            val responses = targets.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        val result = when {
                            querySnapshot.isNotBlank() -> manager.search(source.id, querySnapshot, page = 1)
                            sortMode == "popular" -> manager.popular(source.id, page = 1)
                            else -> manager.latest(source.id, page = 1)
                        }
                        result.map { it.copy(sourceId = source.id, sourceName = source.name) } to null
                    }.getOrElse { emptyList<NovelItem>() to (it.message ?: "تعذر تحميل ${source.name}.") }
                }
            }.awaitAll()
            val novels = responses.flatMap { it.first }
                .filterNot { MslAdultContentFilter.isEnabled(context) && it.isAdultContent() }
                .distinctBy { "${it.sourceId}:${it.path}" }
                .let { items ->
                    if (sortMode ==
                        "rating"
                    ) {
                        items.sortedByDescending { it.ratingValue() ?: -1.0 }
                    } else {
                        items
                    }
                }
            val latestNovel = if (querySnapshot.isNotBlank()) {
                null
            } else {
                latestDeferred?.await()?.getOrNull() ?: novels.firstOrNull()
            }
            if (state.query.trim() == querySnapshot && state.selectedSource == sid && state.allSources == allSources) {
                state = state.copy(
                    novels = novels,
                    latestNovel = latestNovel,
                    loading = false,
                    hasMore = responses.any { it.first.isNotEmpty() },
                    error = if (novels.isEmpty()) responses.mapNotNull { it.second }.firstOrNull() else null,
                )
            }
        }
    }

    fun loadMore() {
        val snapshot = state
        if (snapshot.loading || snapshot.loadingMore || !snapshot.hasMore || snapshot.showFavorites) return
        val targets = if (snapshot.allSources) {
            snapshot.sources
        } else {
            snapshot.sources.filter {
                it.id ==
                    snapshot.selectedSource
            }
        }
        if (targets.isEmpty()) return
        val nextPage = snapshot.page + 1
        state = snapshot.copy(loadingMore = true)
        scope.launch {
            val responses = targets.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        val result = when {
                            snapshot.query.isNotBlank() -> manager.search(
                                source.id,
                                snapshot.query.trim(),
                                page = nextPage,
                            )
                            snapshot.sortMode == "popular" -> manager.popular(source.id, page = nextPage)
                            else -> manager.latest(source.id, page = nextPage)
                        }
                        result.map { it.copy(sourceId = source.id, sourceName = source.name) } to null
                    }.getOrElse {
                        emptyList<NovelItem>() to (it.message ?: "تعذر تحميل صفحة إضافية من ${source.name}.")
                    }
                }
            }.awaitAll()
            val additions = responses.flatMap { it.first }
                .filterNot { MslAdultContentFilter.isEnabled(context) && it.isAdultContent() }
                .let { items ->
                    if (snapshot.sortMode ==
                        "rating"
                    ) {
                        items.sortedByDescending { it.ratingValue() ?: -1.0 }
                    } else {
                        items
                    }
                }
            if (state.selectedSource == snapshot.selectedSource && state.query == snapshot.query &&
                state.page == snapshot.page
            ) {
                state = state.copy(
                    novels = (state.novels + additions)
                        .distinctBy { "${it.sourceId}:${it.path}" }
                        .let { items -> if (snapshot.sortMode == "rating") items.sortedByDescending { it.ratingValue() ?: -1.0 } else items },
                    page = nextPage,
                    loadingMore = false,
                    hasMore = responses.any { it.first.isNotEmpty() },
                    error = if (additions.isEmpty()) responses.mapNotNull { it.second }.firstOrNull() else state.error,
                )
            }
        }
    }

    LaunchedEffect(Unit) { reload(state.selectedSource, allSources = state.allSources) }

    val installEntry: (NovelRepositoryEntry) -> Unit = { entry ->
        state = state.copy(installingId = entry.id, error = null, info = null)
        scope.launch {
            val result = runCatching { manager.install(entry, entry.repositoryUrl ?: state.repoUrl.trim()) }
            if (result.isFailure) {
                state =
                    state.copy(installingId = null, error = result.exceptionOrNull()?.message ?: "تعذّر تثبيت المصدر.")
            } else {
                val installed = result.getOrThrow()
                Toast.makeText(context, "تم تثبيت ${installed.name} بنجاح", Toast.LENGTH_LONG).show()
                state = state.copy(installingId = null, repoDialog = false, sourcesDialog = false)
                reload(installed.id, allSources = false)
            }
        }
    }

    NovelShell(
        state = state,
        favoriteKeys = favoriteKeys,
        onToggleFavorite = { item ->
            val sid = item.sourceId ?: state.selectedSource
            if (sid != null) {
                scope.launch {
                    val error = MslFavorites.toggle(
                        context,
                        MslFavoriteItem(
                            kind = MslFavoriteKind.NOVEL,
                            itemKey = "$sid::${item.path}",
                            title = item.name,
                            cover = item.cover,
                            sourceId = sid,
                        ),
                    )
                    if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        },
        onToggleFavoriteFilter = { state = state.copy(showFavorites = !state.showFavorites) },
        onShowSources = {
            state =
                state.copy(
                    sourcesDialog = true,
                    sourceLanguageFilter = "supported",
                    catalogLoading = true,
                    error = null,
                    info = null,
                )
            scope.launch {
                val result = runCatching { manager.loadRepositories() }
                state = state.copy(
                    repoEntries = result.getOrDefault(emptyList()),
                    savedRepositories = withContext(Dispatchers.IO) { manager.repositories() },
                    catalogLoading = false,
                    error = result.exceptionOrNull()?.message,
                    info = result.getOrNull()?.let { "تم تحميل ${it.size} مصدرًا من الفهارس" },
                )
            }
        },
        onSearch = { query ->
            state = state.copy(query = query, info = null, loading = true, error = null)
            reload(state.selectedSource, state.allSources)
        },
        onSelectSource = { sourceId ->
            uiPrefs.edit().putString("last_source", sourceId).apply()
            reload(sourceId, allSources = false)
        },
        onSelectAllSources = { reload(sourceId = null, allSources = true) },
        onSelectSort = { sort ->
            uiPrefs.edit().putString("sort_mode", sort).apply()
            state = state.copy(sortMode = sort, loading = true)
            reload(state.selectedSource, state.allSources)
        },
        onToggleLayout = {
            val layout = if (state.layoutMode == "grid") "list" else "grid"
            uiPrefs.edit().putString("layout_mode", layout).apply()
            state = state.copy(layoutMode = layout)
        },
        onRetry = { reload(state.selectedSource, state.allSources) },
        onLoadMore = { loadMore() },
        onAddRepository = {
            state = state.copy(repoDialog = true, error = null, info = null)
            scope.launch {
                state =
                    state.copy(savedRepositories = withContext(Dispatchers.IO) { manager.repositories() })
            }
        },
        onInputModeChange = {
            state = state.copy(inputMode = it, repoEntries = emptyList(), error = null, info = null)
        },
        onRepoUrlChange = { state = state.copy(repoUrl = it, error = null, info = null) },
        onInstall = installEntry,
        onInstallDirect = {
            val url = state.repoUrl.trim()
            if (!isValidNovelHttpUrl(url)) {
                state = state.copy(error = "الرابط غير صالح. أدخل عنوانًا كاملًا يبدأ بـ https:// أو http://")
            } else {
                state = state.copy(installingId = "direct", error = null, info = null)
                scope.launch {
                    val result = runCatching { manager.installFromUrl(url, repositoryUrl = null) }
                    if (result.isFailure) {
                        state =
                            state.copy(
                                installingId = null,
                                error =
                                result.exceptionOrNull()?.message ?: "تعذّر تثبيت الإضافة.",
                            )
                    } else {
                        val installed = result.getOrThrow()
                        Toast.makeText(context, "تم تثبيت ${installed.name} بنجاح", Toast.LENGTH_LONG).show()
                        state = state.copy(installingId = null, repoDialog = false)
                        reload(installed.id)
                    }
                }
            }
        },
        onOpen = { item ->
            val sourceId = item.sourceId ?: state.selectedSource
            if (sourceId != null) navigator.push(NovelDetailsScreen(sourceId, item.path, item.name))
        },
        onContinueRead = { item ->
            val sourceId = item.sourceId ?: state.selectedSource
            if (sourceId != null) {
                scope.launch {
                    val history = withContext(Dispatchers.IO) {
                        NovelHistoryStore(context).all().firstOrNull {
                            it.sourceId == sourceId && it.novelPath == item.path
                        }
                    }
                    if (history == null) {
                        navigator.push(NovelDetailsScreen(sourceId, item.path, item.name))
                    } else {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                manager.ensureLoaded()
                                manager.details(sourceId, item.path)
                            }
                        }
                        val details = result.getOrNull()
                        val chapters = details?.chapters.orEmpty()
                        if (details == null || chapters.isEmpty() ||
                            (MslAdultContentFilter.isEnabled(context) && details.isAdultContent())
                        ) {
                            navigator.push(NovelDetailsScreen(sourceId, item.path, item.name))
                        } else {
                            val index = chapters.indexOfFirst { it.path == history.chapterPath }.takeIf { it >= 0 }
                                ?: history.chapterIndex.coerceIn(0, chapters.lastIndex)
                            navigator.push(
                                NovelReaderScreen(
                                    sourceId = sourceId,
                                    novelPath = details.path,
                                    novelName = details.name ?: item.name,
                                    novelCover = details.cover ?: item.cover,
                                    chapters = chapters,
                                    chapterIndex = index,
                                    novelDescription = details.summary,
                                ),
                            )
                        }
                    }
                }
            }
        },
        onNavigate = { destination ->
            val target = when (destination) {
                "home" -> HomeScreen.Tab.Home
                "library" -> HomeScreen.Tab.Library()
                "browse" -> HomeScreen.Tab.Browse(false)
                "account" -> HomeScreen.Tab.Account
                else -> null
            }
            if (target != null) scope.launch { HomeScreen.openTab(target) }
        },
        onCloseRepo = { state = state.copy(repoDialog = false, error = null) },
        onLoadRepo = {
            val url = state.repoUrl.trim()
            if (!isValidNovelHttpUrl(url)) {
                state = state.copy(error = "الرابط غير صالح. الصق رابط ملف مستودع يبدأ بـ https:// أو http://")
            } else {
                state = state.copy(catalogLoading = true, error = null, info = null)
                scope.launch {
                    val result = runCatching { manager.loadRepository(url) }
                    state = state.copy(
                        repoEntries = result.getOrDefault(emptyList()),
                        savedRepositories = withContext(Dispatchers.IO) { manager.repositories() },
                        catalogLoading = false,
                        error = result.exceptionOrNull()?.message,
                        info = result.getOrNull()?.let { "تم العثور على ${it.size} مصدرًا" },
                    )
                }
            }
        },
        onRemoveRepository = { url ->
            scope.launch {
                manager.removeRepository(url)
                state = state.copy(
                    savedRepositories = withContext(Dispatchers.IO) { manager.repositories() },
                    info = "تمت إزالة المستودع من القائمة",
                )
            }
        },
        onRemoveSource = { sourceId ->
            scope.launch {
                val name = state.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId
                manager.uninstall(sourceId)
                Toast.makeText(context, "تمت إزالة $name من المصادر المثبتة", Toast.LENGTH_SHORT).show()
                state = state.copy(info = null)
                reload(state.selectedSource, state.allSources)
            }
        },
    )

    if (state.sourcesDialog) {
        NovelSourcesCatalogDialog(
            entries = state.repoEntries,
            installedSources = state.sources,
            installingId = state.installingId,
            languageFilter = state.sourceLanguageFilter,
            loading = state.catalogLoading,
            error = state.error,
            onLanguageFilter = { state = state.copy(sourceLanguageFilter = it) },
            onInstall = installEntry,
            onRemoveSource = { sourceId ->
                scope.launch {
                    val name = state.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId
                    manager.uninstall(sourceId)
                    state = state.copy(info = "تمت إزالة $name من المصادر المثبتة")
                    reload(state.selectedSource, state.allSources)
                }
            },
            onReload = {
                state = state.copy(catalogLoading = true, error = null)
                scope.launch {
                    val result = runCatching { manager.loadRepositories() }
                    state = state.copy(
                        repoEntries = result.getOrDefault(emptyList()),
                        catalogLoading = false,
                        error = result.exceptionOrNull()?.message,
                    )
                }
            },
            onDismiss = { state = state.copy(sourcesDialog = false, error = null) },
        )
    }
}

@Composable
private fun NovelShell(
    state: NovelUiState,
    favoriteKeys: Set<String>,
    onToggleFavorite: (NovelItem) -> Unit,
    onToggleFavoriteFilter: () -> Unit,
    onShowSources: () -> Unit,
    onSearch: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onSelectAllSources: () -> Unit,
    onSelectSort: (String) -> Unit,
    onToggleLayout: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onAddRepository: () -> Unit,
    onInputModeChange: (NovelInputMode) -> Unit,
    onRepoUrlChange: (String) -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onInstallDirect: () -> Unit,
    onRemoveRepository: (String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onOpen: (NovelItem) -> Unit,
    onContinueRead: (NovelItem) -> Unit,
    onCloseRepo: () -> Unit,
    onLoadRepo: () -> Unit,
) {
    var showAll by remember(state.selectedSource, state.query, state.allSources) {
        mutableStateOf(state.query.isNotBlank())
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val hero = state.latestNovel?.takeIf { state.query.isBlank() && !state.showFavorites && !state.allSources && !showAll }
    val visibleNovels = if (state.showFavorites) {
        state.novels.filter { item ->
            val resultSource = item.sourceId ?: state.selectedSource
            resultSource != null && "$resultSource::${item.path}" in favoriteKeys
        }
    } else {
        state.novels
    }
    val feedNovels = if (hero != null) {
        visibleNovels.filterNot { it.sourceId == hero.sourceId && it.path == hero.path }
    } else {
        visibleNovels
    }
    val displayedNovels = if (showAll) feedNovels else feedNovels.take(4)

    LaunchedEffect(
        state.layoutMode,
        showAll,
        feedNovels.size,
        state.loading,
        state.loadingMore,
        state.hasMore,
        state.showFavorites,
    ) {
        if (!showAll || state.showFavorites || state.loading || !state.hasMore) return@LaunchedEffect
        if (state.layoutMode == "grid") {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { lastVisible ->
                if (lastVisible >= feedNovels.lastIndex - 3) onLoadMore()
            }
        } else {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { lastVisible ->
                if (lastVisible >= feedNovels.lastIndex - 3) onLoadMore()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(NovelColors.background),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(NovelColors.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_slime_logo),
                    contentDescription = "شعار التطبيق",
                    modifier = Modifier.size(42.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "الروايات",
                        color = NovelColors.textPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "اكتشف حكايتك القادمة",
                        color = NovelColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalIconButton(
                    onClick = onAddRepository,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "إضافة مصدر روايات" },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = NovelColors.accent,
                        contentColor = Color.White,
                    ),
                ) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light) }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("ابحث عن رواية أو مؤلف…", color = NovelColors.textMuted) },
                leadingIcon = { Text("⌕", color = NovelColors.accent, fontSize = 24.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NovelColors.textPrimary,
                    unfocusedTextColor = NovelColors.textPrimary,
                    focusedContainerColor = NovelColors.background,
                    unfocusedContainerColor = NovelColors.background,
                    focusedBorderColor = NovelColors.accent,
                    unfocusedBorderColor = NovelColors.divider,
                    cursorColor = NovelColors.accent,
                ),
            )

            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.allSources) {
                            "بحث شامل"
                        } else {
                            "${state.sources.firstOrNull {
                                it.id == state.selectedSource
                            }?.name ?: "المصادر"} · ${state.sources.size} مثبتة"
                        },
                        color = NovelColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "مصادر عربية وإنجليزية",
                        color = NovelColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onShowSources, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("استعراض المصادر", color = NovelColors.accent)
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 2.dp)) {
                item {
                    FilterChip(
                        selected = state.allSources,
                        onClick = onSelectAllSources,
                        label = { Text("الكل") },
                        shape = CircleShape,
                        colors = novelChipColors(),
                    )
                }
                item {
                    FilterChip(
                        selected = state.showFavorites,
                        onClick = onToggleFavoriteFilter,
                        label = { Text("مكتبتي") },
                        shape = CircleShape,
                        colors = novelChipColors(),
                    )
                }
                items(state.sources, key = { it.id }) { source ->
                    FilterChip(
                        selected = !state.allSources && state.selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = CircleShape,
                        colors = novelChipColors(),
                    )
                }
            }
        }

        if (hero != null) {
            NovelHeroBanner(item = hero, onOpen = { onOpen(hero) }, onContinue = { onContinueRead(hero) })
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.showFavorites -> "مكتبتي"
                        state.query.isNotBlank() -> "نتائج البحث"
                        showAll -> "كل الروايات"
                        else -> "وصل حديثًا"
                    },
                    color = NovelColors.textPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!showAll && state.query.isBlank() && !state.showFavorites) {
                    Text(
                        "حكايات جديدة أُضيفت مؤخرًا",
                        color = NovelColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!showAll && state.query.isBlank() && !state.showFavorites) {
                TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("عرض الكل", color = NovelColors.accent, fontWeight = FontWeight.SemiBold)
                }
            } else if (showAll && state.query.isBlank() && !state.showFavorites) {
                IconButton(
                    onClick = onToggleLayout,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription =
                            if (state.layoutMode == "grid") "التبديل إلى عرض القائمة" else "التبديل إلى عرض الشبكة"
                    },
                ) { Text(if (state.layoutMode == "grid") "☰" else "▦", color = NovelColors.accent, fontSize = 22.sp) }
            }
        }

        if (showAll && state.query.isBlank() && !state.showFavorites) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(listOf("new" to "الأحدث", "popular" to "الأشهر", "rating" to "الأعلى تقييمًا")) { option ->
                    FilterChip(
                        selected = state.sortMode == option.first,
                        onClick = { onSelectSort(option.first) },
                        label = { Text(option.second, maxLines = 1) },
                        shape = CircleShape,
                        colors = novelChipColors(),
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.info?.let { Text(it, color = NovelColors.success, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        Modifier.weight(1f),
                        color = NovelColors.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("إعادة المحاولة", color = NovelColors.accent)
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> NovelSkeletonResults(state.layoutMode)
                state.sources.isEmpty() -> EmptyNovelSources(state.error, onAddRepository, onRetry)
                state.showFavorites && visibleNovels.isEmpty() -> EmptyFavorites()
                displayedNovels.isEmpty() -> EmptyNovelResults(state.query, state.error != null, onRetry)
                state.layoutMode == "grid" -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(displayedNovels, key = { "${it.sourceId}:${it.path}" }) { item ->
                        NovelCard(
                            item = item,
                            onOpen = onOpen,
                            isFavorite =
                            (item.sourceId ?: state.selectedSource)?.let { sourceId ->
                                "$sourceId::${item.path}" in
                                    favoriteKeys
                            } ==
                                true,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                    if (state.loadingMore && showAll) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(22.dp),
                                    color = NovelColors.accent,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(displayedNovels, key = { "${it.sourceId}:${it.path}" }) { item ->
                        NovelListRow(
                            item = item,
                            onOpen = onOpen,
                            isFavorite =
                            (item.sourceId ?: state.selectedSource)?.let { sourceId ->
                                "$sourceId::${item.path}" in
                                    favoriteKeys
                            } ==
                                true,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                    if (state.loadingMore && showAll) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(22.dp),
                                    color = NovelColors.accent,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }

        NovelBottomNavigation(onNavigate = onNavigate)
    }

    if (state.repoDialog) {
        NovelAddSourceSheet(
            state = state,
            onDismiss = onCloseRepo,
            onModeChange = onInputModeChange,
            onUrlChange = onRepoUrlChange,
            onLoadRepository = onLoadRepo,
            onInstall = onInstall,
            onInstallDirect = onInstallDirect,
            onRemoveRepository = onRemoveRepository,
            installedSources = state.sources,
        )
    }
}

@Composable
private fun novelChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = NovelColors.surfaceRaised,
    labelColor = NovelColors.textSecondary,
    selectedContainerColor = NovelColors.accent.copy(alpha = 0.12f),
    selectedLabelColor = NovelColors.accent,
)

@Composable
private fun NovelHeroBanner(item: NovelItem, onOpen: () -> Unit, onContinue: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = NovelColors.cardShape,
        colors = CardDefaults.cardColors(containerColor = NovelColors.accent),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onContinue),
        ) {
            Box(Modifier.fillMaxSize().background(NovelColors.heroGradient))
            item.cover?.takeIf(String::isNotBlank)?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color(0xEE251A55), Color(0xAA33216F), Color.Transparent)),
                ),
            )
            Column(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(0.74f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "اختيار اليوم",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = onContinue,
                    modifier = Modifier.heightIn(min = 44.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = NovelColors.accent,
                    ),
                ) { Text("ابدأ القراءة", fontWeight = FontWeight.Bold) }
            }
            item.sourceName?.let { source ->
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(10.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.35f),
                ) {
                    Text(
                        source,
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelBottomNavigation(onNavigate: (String) -> Unit) {
    Surface(
        color = NovelColors.surface,
        border = BorderStroke(1.dp, NovelColors.divider),
        tonalElevation = 0.dp,
    ) {
        NavigationBar(
            containerColor = NovelColors.surface,
            contentColor = NovelColors.textSecondary,
            tonalElevation = 0.dp,
        ) {
            val items = listOf(
                Triple("home", "الرئيسية", mihon.icons.materialsymbols.MaterialSymbols.Rounded.LocalLibrary),
                Triple("library", "المكتبة", mihon.icons.materialsymbols.MaterialSymbols.Rounded.CollectionsBookmark),
                Triple(
                    "novels",
                    "الروايات",
                    mihon.icons.materialsymbols.MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
                ),
                Triple("browse", "تصفّح", mihon.icons.materialsymbols.MaterialSymbols.Rounded.Explore),
                Triple("account", "حسابي", mihon.icons.materialsymbols.MaterialSymbols.Rounded.Person),
            )
            items.forEach { (route, label, icon) ->
                NavigationBarItem(
                    selected = route == "novels",
                    onClick = { if (route != "novels") onNavigate(route) },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(label, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NovelColors.accent,
                        selectedTextColor = NovelColors.accent,
                        indicatorColor = NovelColors.accent.copy(alpha = 0.12f),
                        unselectedIconColor = NovelColors.textSecondary,
                        unselectedTextColor = NovelColors.textSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun NovelAddSourceSheet(
    state: NovelUiState,
    installedSources: List<NovelSourceRuntime>,
    onDismiss: () -> Unit,
    onModeChange: (NovelInputMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onLoadRepository: () -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onInstallDirect: () -> Unit,
    onRemoveRepository: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NovelColors.surface,
        contentColor = NovelColors.textPrimary,
        shape = MaterialTheme.shapes.extraLarge,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(
                0.92f,
            ).navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("مصادر الروايات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "أضف مستودع LNReader أو ثبّت ملف plugin.js مباشرة.",
                color = NovelColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.inputMode == NovelInputMode.REPOSITORY,
                    onClick = { onModeChange(NovelInputMode.REPOSITORY) },
                    label = { Text("مستودع") },
                    shape = CircleShape,
                    colors = novelChipColors(),
                )
                FilterChip(
                    selected = state.inputMode == NovelInputMode.DIRECT_URL,
                    onClick = { onModeChange(NovelInputMode.DIRECT_URL) },
                    label = { Text("رابط مباشر") },
                    shape = CircleShape,
                    colors = novelChipColors(),
                )
            }
            OutlinedTextField(
                value = state.repoUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = NovelColors.compactCardShape,
                label = {
                    Text(
                        if (state.inputMode ==
                            NovelInputMode.REPOSITORY
                        ) {
                            "رابط ملف plugins.min.json"
                        } else {
                            "رابط ملف plugin.js"
                        },
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NovelColors.textPrimary,
                    unfocusedTextColor = NovelColors.textPrimary,
                    focusedBorderColor = NovelColors.accent,
                    unfocusedBorderColor = NovelColors.divider,
                    focusedLabelColor = NovelColors.accent,
                    unfocusedLabelColor = NovelColors.textMuted,
                    cursorColor = NovelColors.accent,
                ),
            )
            if (!state.error.isNullOrBlank()) {
                Text(
                    state.error,
                    color = NovelColors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.info?.let { Text(it, color = NovelColors.success, style = MaterialTheme.typography.bodySmall) }

            if (state.inputMode == NovelInputMode.REPOSITORY) {
                Button(
                    onClick = onLoadRepository,
                    enabled = !state.catalogLoading && isValidNovelHttpUrl(state.repoUrl),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovelColors.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    if (state.catalogLoading &&
                        state.installingId == null
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("تحميل المستودع")
                    }
                }
                if (state.savedRepositories.isNotEmpty()) {
                    Text("المستودعات المحفوظة", color = NovelColors.textPrimary, fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.heightIn(max = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.savedRepositories, key = { it }) { repository ->
                            Row(
                                Modifier.fillMaxWidth().background(
                                    NovelColors.surfaceRaised,
                                    NovelColors.compactCardShape,
                                ).padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    repository,
                                    Modifier.weight(1f).clickable { onUrlChange(repository) }.padding(vertical = 10.dp),
                                    color = NovelColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { onRemoveRepository(repository) },
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription =
                                            "إزالة المستودع المحفوظ"
                                    },
                                ) { Text("×", color = NovelColors.danger, fontSize = 22.sp) }
                            }
                        }
                    }
                }
                val supportedEntries = state.repoEntries.filter { it.lang.toNovelLanguageCode() != null }
                if (state.repoEntries.isNotEmpty()) {
                    Text(
                        "مصادر عربية وإنجليزية · ${supportedEntries.size}",
                        color = NovelColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (supportedEntries.isEmpty()) {
                        Text("لم يعثر الفهرس على مصادر عربية أو إنجليزية.", color = NovelColors.textSecondary)
                    } else {
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            items(supportedEntries, key = { "${it.id}:${it.url}" }) { entry ->
                                val installed = installedSources.firstOrNull { it.id == entry.id }
                                val updateAvailable =
                                    installed != null &&
                                        isNovelVersionNewer(entry.version, installed.installed.metadata.version)
                                Row(
                                    Modifier.fillMaxWidth().background(
                                        NovelColors.surfaceRaised,
                                        NovelColors.compactCardShape,
                                    ).padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            color = NovelColors.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOfNotNull(entry.lang?.uppercase(), entry.version).joinToString(" • "),
                                            color = NovelColors.textMuted,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    if (state.installingId == entry.id) {
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            color = NovelColors.accent,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Button(
                                            onClick = { onInstall(entry) },
                                            enabled = installed == null || updateAvailable,
                                            modifier = Modifier.heightIn(min = 44.dp),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (installed != null &&
                                                    !updateAvailable
                                                ) {
                                                    NovelColors.surfaceHighest
                                                } else {
                                                    NovelColors.accent
                                                },
                                                contentColor = if (installed != null &&
                                                    !updateAvailable
                                                ) {
                                                    NovelColors.textSecondary
                                                } else {
                                                    Color.White
                                                },
                                            ),
                                        ) {
                                            Text(
                                                if (installed ==
                                                    null
                                                ) {
                                                    "تثبيت"
                                                } else if (updateAvailable) {
                                                    "تحديث"
                                                } else {
                                                    "مثبّت"
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Button(
                    onClick = onInstallDirect,
                    enabled = state.installingId != "direct" && isValidNovelHttpUrl(state.repoUrl),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovelColors.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    if (state.installingId ==
                        "direct"
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("تنزيل وتثبيت المصدر")
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            Surface(color = NovelColors.surfaceRaised, shape = NovelColors.compactCardShape) {
                Text(
                    "تنبيه: المصدر يشغّل JavaScript تابعًا لجهة خارجية. ثبّت الروابط التي تثق بها فقط.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    color = NovelColors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyNovelSources(error: String?, onAdd: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "لا توجد مصادر روايات مثبتة",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = NovelColors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error ?: "أضف مستودعًا ثم ثبّت المصادر التي تريدها.",
            color = if (error == null) NovelColors.textSecondary else NovelColors.danger,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = NovelColors.accent, contentColor = Color.White),
            ) { Text("إضافة مستودع") }
            if (error != null) {
                OutlinedButton(onClick = onRetry) { Text("إعادة المحاولة", color = NovelColors.accent) }
            }
        }
    }
}

@Composable
private fun NovelCover(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = NovelColors.compactCardShape,
) {
    Box(modifier.clip(shape)) {
        Box(Modifier.fillMaxSize().background(NovelColors.coverFallback))
        url?.takeIf(String::isNotBlank)?.let { cover ->
            AsyncImage(
                model = cover,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NovelCard(
    item: NovelItem,
    onOpen: (NovelItem) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (NovelItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription =
                "${item.name}. اضغط للفتح واضغط مطولًا ${if (isFavorite) "لإزالة الرواية من المكتبة" else "لإضافتها إلى المكتبة"}"
        },
        shape = NovelColors.cardShape,
        colors = CardDefaults.cardColors(containerColor = NovelColors.surface),
        border = BorderStroke(1.dp, NovelColors.divider),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onToggleFavorite(item) }),
        ) {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                NovelCover(item.cover, item.name, Modifier.fillMaxSize(), NovelColors.cardShape)
                if (isFavorite) {
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = CircleShape,
                        color = NovelColors.accent,
                    ) {
                        Text(
                            "في مكتبتي",
                            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.name,
                    color = NovelColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                )
                item.ratingValue()?.let { rating ->
                    Text(
                        "★ ${"%.1f".format(rating)}",
                        color = NovelColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelListRow(
    item: NovelItem,
    onOpen: (NovelItem) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (NovelItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription =
                "${item.name}. اضغط للفتح واضغط مطولًا ${if (isFavorite) "لإزالة الرواية من المكتبة" else "لإضافتها إلى المكتبة"}"
        },
        shape = NovelColors.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = NovelColors.surface),
        border = BorderStroke(1.dp, NovelColors.divider),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {
                onOpen(item)
            }, onLongClick = { onToggleFavorite(item) }).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelCover(item.cover, item.name, Modifier.size(width = 58.dp, height = 78.dp), RoundedCornerShape(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name,
                    color = NovelColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.sourceName?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = NovelColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.ratingValue()?.let {
                    Text(
                        "★ ${"%.1f".format(it)}",
                        color = NovelColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (isFavorite) Text("✓", color = NovelColors.accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun NovelCoverSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "novelCoverShimmer")
    val shift by transition.animateFloat(
        initialValue = -420f,
        targetValue = 520f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1250, easing = LinearEasing)),
        label = "novelCoverShimmerShift",
    )
    val brush = Brush.linearGradient(
        colors = listOf(NovelColors.surfaceRaised, NovelColors.border.copy(alpha = 0.75f), NovelColors.surfaceRaised),
        start = Offset(shift, 0f),
        end = Offset(shift + 360f, 360f),
    )
    Box(modifier.background(brush))
}

@Composable
private fun NovelSkeletonResults(layoutMode: String) {
    if (layoutMode == "grid") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(6) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovelCoverSkeleton(Modifier.fillMaxWidth().height(214.dp).clip(NovelColors.cardShape))
                    NovelCoverSkeleton(Modifier.fillMaxWidth(0.78f).height(14.dp).clip(NovelColors.pillShape))
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(6) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NovelCoverSkeleton(Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NovelCoverSkeleton(Modifier.fillMaxWidth(0.78f).height(14.dp).clip(NovelColors.pillShape))
                        NovelCoverSkeleton(Modifier.fillMaxWidth(0.48f).height(12.dp).clip(NovelColors.pillShape))
                    }
                }
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
            "لا توجد روايات مفضلة",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = NovelColors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "أضف الروايات التي تريد الرجوع إليها لاحقًا.",
            color = NovelColors.textSecondary,
        )
    }
}

@Composable
private fun EmptyNovelResults(query: String, hasError: Boolean, onRetry: () -> Unit) {
    val message = when {
        hasError -> "تعذر تحميل الروايات من هذا المصدر."
        query.isNotBlank() -> "لم يتم العثور على نتائج لـ «${query.trim()}»."
        else -> "لا توجد روايات متاحة لهذا المصدر حاليًا."
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NovelColors.textPrimary,
        )
        if (hasError) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = NovelColors.accent)) {
                Text("إعادة المحاولة")
            }
        } else if (query.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("جرّب عنوانًا آخر أو تحقق من المصدر المحدد.", color = NovelColors.textSecondary)
        }
    }
}

class NovelDetailsScreen(
    private val sourceId: String,
    private val path: String,
    private val fallbackName: String,
) : Screen() {
    @Composable
    override fun Content() {
        NovelTheme { DetailsContent() }
    }

    @Composable
    private fun DetailsContent() {
        val context = LocalContext.current
        var showNovelComments by remember { mutableStateOf(false) }
        var showDownloadAllConfirm by remember { mutableStateOf(false) }
        var detailsTab by remember(sourceId, path) { mutableStateOf("chapters") }
        var detailsRevision by remember(sourceId, path) { mutableIntStateOf(0) }
        var bulkProgress by remember { mutableIntStateOf(0) }
        var bulkTotal by remember { mutableIntStateOf(0) }
        var lastRead by remember { mutableStateOf<NovelHistoryEntry?>(null) }
        val historyStore = remember { NovelHistoryStore(context) }
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val readStore = remember { NovelReadStore(context) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val detailsScope = rememberCoroutineScope()
        var readKeys by remember { mutableStateOf(emptySet<String>()) }
        var downloadedPaths by remember { mutableStateOf(emptySet<String>()) }
        var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
        var chapterAccessRevision by remember(sourceId, path) { mutableIntStateOf(0) }
        var downloadingPaths by remember { mutableStateOf(emptySet<String>()) }
        var bulkDownloading by remember { mutableStateOf(false) }
        var chapterOrder by remember(sourceId, path) { mutableStateOf("source") }
        var chapterReadFilter by remember(sourceId, path) { mutableStateOf("all") }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        val favoriteSnapshot by MslFavorites.snapshot.collectAsState()
        val offlineStore = remember { NovelOfflineStore(context) }
        LaunchedEffect(sourceId, path, detailsRevision) {
            error = null
            lastRead = withContext(Dispatchers.IO) {
                historyStore.all().firstOrNull { it.sourceId == sourceId && it.novelPath == path }
            }
            if (!favoriteSnapshot.loaded && !favoriteSnapshot.loading) MslFavorites.load(context)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    manager.ensureLoaded()
                    manager.details(sourceId, path)
                }
            }
            val fetched = r.getOrNull()
            if (MslAdultContentFilter.isEnabled(context) && fetched?.isAdultContent() == true) {
                details = null
                error = "تم حجب هذا العمل لأنه مصنّف أو موسوم بمحتوى +18."
            } else {
                details = fetched
                error = r.exceptionOrNull()?.message
            }
        }
        LaunchedEffect(readStore) { readKeys = withContext(Dispatchers.IO) { readStore.all() } }
        LaunchedEffect(details) {
            val current = details ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { MslChapterAccess.refresh(context) }
            chapterAccessRevision++
            downloadedPaths = withContext(Dispatchers.IO) {
                current.chapters.orEmpty().filter {
                    offlineStore.contains(sourceId, current.path, it.path)
                }.map { it.path }.toSet()
            }
        }
        DisposableEffect(lifecycleOwner, readStore) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    detailsScope.launch {
                        readKeys = withContext(Dispatchers.IO) { readStore.all() }
                        lastRead = withContext(Dispatchers.IO) {
                            historyStore.all().firstOrNull { it.sourceId == sourceId && it.novelPath == path }
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val isFavorite = favoriteSnapshot.items.any {
            it.kind == MslFavoriteKind.NOVEL && it.itemKey == "$sourceId::$path"
        }
        fun requestDownload(index: Int, chapter: NovelChapter) {
            val current = details ?: return
            if (chapter.path in downloadingPaths || chapter.path in downloadedPaths) return
            downloadingPaths = downloadingPaths + chapter.path
            detailsScope.launch {
                try {
                    val failure = try {
                        saveNovelOfflineChapter(
                            context,
                            manager,
                            offlineStore,
                            sourceId,
                            current,
                            fallbackName,
                            index,
                            chapter,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error.message ?: "تعذّر تحميل ${chapter.name}."
                    }
                    if (failure == null) {
                        downloadedPaths = downloadedPaths + chapter.path
                        chapterAccessRevision++
                        Toast.makeText(context, "تم تحميل ${chapter.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    downloadingPaths = downloadingPaths - chapter.path
                }
            }
        }
        fun toggleNovelFavorite() {
            detailsScope.launch {
                val current = details
                val failure = MslFavorites.toggle(
                    context,
                    MslFavoriteItem(
                        kind = MslFavoriteKind.NOVEL,
                        itemKey = "$sourceId::$path",
                        title = current?.name ?: fallbackName,
                        cover = current?.cover,
                        sourceId = sourceId,
                    ),
                )
                if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
            }
        }

        fun openChapter(chapters: List<NovelChapter>, index: Int, sourceIndices: List<Int> = chapters.indices.toList()) {
            val current = details ?: return
            if (chapters.isEmpty()) return
            navigator.push(
                NovelReaderScreen(
                    sourceId = sourceId,
                    novelPath = current.path,
                    novelName = current.name ?: fallbackName,
                    novelCover = current.cover,
                    chapters = chapters,
                    chapterIndex = index.coerceIn(0, chapters.lastIndex),
                    novelDescription = current.summary,
                    sourceChapterIndices = sourceIndices,
                ),
            )
        }

        fun startBulkDownload(selection: List<Pair<Int, NovelChapter>>) {
            val current = details ?: return
            val pending = selection.filterNot { it.second.path in downloadedPaths }
            if (pending.isEmpty() || bulkDownloading) return
            bulkDownloading = true
            bulkProgress = 0
            bulkTotal = pending.size
            detailsScope.launch {
                var succeeded = 0
                var failed = 0
                try {
                    val keys = pending.mapNotNull { (_, chapter) ->
                        MslChapterAccess.novelKey(sourceId, current.path, chapter.path)
                    }.distinct()
                    if (keys.isEmpty() || keys.size > 100) {
                        Toast.makeText(
                            context,
                            "يمكن فتح 100 فصل كحد أقصى في العملية الواحدة.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    val unlock = withContext(Dispatchers.IO) { MslChapterAccess.unlock(context, keys) }
                    if (unlock == null || !unlock.allowed) {
                        val message = if (unlock?.status == "insufficient") {
                            "الرصيد غير كافٍ. الرصيد: ${unlock.balance ?: 0}، المطلوب: ${unlock.required} عملة."
                        } else {
                            "تعذّر فتح الفصول. تحقق من اتصال الإنترنت ثم أعد المحاولة."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    chapterAccessRevision++
                    pending.forEach { (chapterIndex, chapter) ->
                        downloadingPaths = downloadingPaths + chapter.path
                        val failure = try {
                            saveNovelOfflineChapter(
                                context,
                                manager,
                                offlineStore,
                                sourceId,
                                current,
                                fallbackName,
                                chapterIndex,
                                chapter,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            error.message ?: "تعذّر تحميل ${chapter.name}."
                        } finally {
                            downloadingPaths = downloadingPaths - chapter.path
                        }
                        if (failure == null) {
                            downloadedPaths = downloadedPaths + chapter.path
                            succeeded++
                        } else {
                            failed++
                            Toast.makeText(context, "${chapter.name}: $failure", Toast.LENGTH_LONG).show()
                        }
                        bulkProgress = succeeded + failed
                    }
                } finally {
                    bulkDownloading = false
                    selectedPaths = emptySet()
                }
                Toast.makeText(context, "اكتمل التحميل: $succeeded ناجح، $failed تعذّر", Toast.LENGTH_LONG).show()
            }
        }

        Scaffold(
            topBar = {
                NovelToolbar(
                    title = {
                        Text(details?.name ?: fallbackName)
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.pop() },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "العودة إلى الروايات" },
                        ) { Text("‹", fontSize = 32.sp) }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val current = details
                                val text = listOfNotNull(
                                    current?.name ?: fallbackName,
                                    manager.installed().firstOrNull {
                                        it.id ==
                                            sourceId
                                    }?.name,
                                    current?.path,
                                ).joinToString("\n")
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "مشاركة الرواية"))
                            },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "مشاركة الرواية" },
                        ) { Text("↗", color = NovelColors.textSecondary, fontSize = 21.sp) }
                        IconButton(
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription =
                                    if (isFavorite) "إزالة $fallbackName من مكتبتي" else "حفظ $fallbackName في مكتبتي"
                            },
                            onClick = { toggleNovelFavorite() },
                        ) {
                            Text(
                                if (isFavorite) "▣" else "▢",
                                fontSize = 23.sp,
                                color = if (isFavorite) NovelColors.accent else NovelColors.textSecondary,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val d = details
            if (d == null) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    if (error == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(color = NovelColors.accent)
                            Text("جارٍ تحميل تفاصيل الرواية…", color = NovelColors.textSecondary)
                        }
                    } else {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(error.orEmpty(), color = NovelColors.danger)
                            Button(onClick = { detailsRevision++ }, shape = CircleShape) { Text("إعادة المحاولة") }
                        }
                    }
                }
            } else {
                val chapters = d.chapters.orEmpty()
                val resumeIndex = lastRead?.let { history ->
                    chapters.indexOfFirst { it.path == history.chapterPath }.takeIf { it >= 0 }
                        ?: history.chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                } ?: 0
                val sourceName = manager.installed().firstOrNull { it.id == sourceId }?.name
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 30.dp)) {
                    item {
                        Box(Modifier.fillMaxWidth().height(270.dp)) {
                            NovelCover(
                                d.cover,
                                d.name ?: fallbackName,
                                Modifier.fillMaxSize(),
                                RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))),
                                ),
                            )
                            Column(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                sourceName?.let {
                                    Text(
                                        it,
                                        color = Color.White.copy(alpha = 0.82f),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    d.name ?: fallbackName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${chapters.size} فصل${d.status?.takeIf(String::isNotBlank)?.let {
                                        " · $it"
                                    }.orEmpty()}",
                                    color = Color.White.copy(alpha = 0.86f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val meta = listOfNotNull(
                                d.author?.takeIf(String::isNotBlank),
                                d.artist?.takeIf(String::isNotBlank),
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    color = NovelColors.textSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            eu.kanade.tachiyomi.mslime.MslSocialStats(
                                eu.kanade.tachiyomi.mslime.MslSocial.titleKey("novel", sourceId, path),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { openChapter(chapters, resumeIndex) },
                                    enabled = chapters.isNotEmpty() && !bulkDownloading,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NovelColors.accent,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        if (lastRead != null &&
                                            chapters.isNotEmpty()
                                        ) {
                                            "أكمل من ${chapters.getOrNull(
                                                resumeIndex,
                                            )?.name ?: "الفصل ${resumeIndex + 1}"}"
                                        } else {
                                            "ابدأ القراءة"
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showDownloadAllConfirm = true },
                                    enabled = chapters.isNotEmpty() && !bulkDownloading && MslWallet.enabled,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    shape = CircleShape,
                                ) {
                                    Text(if (bulkDownloading) "جارٍ التحميل $bulkProgress/$bulkTotal" else "تحميل الكل")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("chapters" to "الفصول", "about" to "نبذة").forEach { (tab, label) ->
                                    FilterChip(
                                        selected = detailsTab == tab,
                                        onClick = { detailsTab = tab },
                                        label = { Text(label) },
                                        shape = CircleShape,
                                        colors = novelChipColors(),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (selectedPaths.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            val selected = chapters.mapIndexedNotNull { index, chapter ->
                                                if (chapter.path in selectedPaths) index to chapter else null
                                            }
                                            startBulkDownload(selected)
                                        },
                                        enabled = !bulkDownloading,
                                        modifier = Modifier.heightIn(min = 44.dp),
                                    ) { Text("تحميل المحدد (${selectedPaths.size})", color = NovelColors.accent) }
                                }
                            }
                            if (detailsTab == "about") {
                                if (!d.summary.isNullOrBlank()) {
                                    Text(
                                        d.summary.orEmpty(),
                                        color = NovelColors.textSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                d.genres?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        "التصنيفات: $it",
                                        color = NovelColors.textSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(onClick = {
                                    showNovelComments = true
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = CircleShape) {
                                    Text("التعليقات")
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "الفصول · ${chapters.size}",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = NovelColors.textPrimary,
                                    )
                                    if (bulkDownloading) {
                                        Text(
                                            "$bulkProgress / $bulkTotal",
                                            color = NovelColors.accent,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                                Text(
                                    "ترتيب الفصول",
                                    color = NovelColors.textSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        "source" to "المصدر",
                                        "asc" to "تصاعدي",
                                        "desc" to "تنازلي",
                                    ).forEach { (value, label) ->
                                        FilterChip(
                                            selected = chapterOrder == value,
                                            onClick = { chapterOrder = value },
                                            label = { Text(label) },
                                            shape = CircleShape,
                                            colors = novelChipColors(),
                                        )
                                    }
                                }
                                Text(
                                    "حالة القراءة",
                                    color = NovelColors.textSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        "all" to "الكل",
                                        "unread" to "غير المقروء",
                                        "read" to "المقروء",
                                    ).forEach { (value, label) ->
                                        FilterChip(
                                            selected = chapterReadFilter == value,
                                            onClick = { chapterReadFilter = value },
                                            label = { Text(label) },
                                            shape = CircleShape,
                                            colors = novelChipColors(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (detailsTab == "chapters") {
                        val orderedChapters = orderNovelChapters(
                            chapters,
                            when (chapterOrder) {
                                "asc" -> false
                                "desc" -> true
                                else -> null
                            },
                        )
                        val visibleChapters = orderedChapters.filter { (_, chapter) ->
                            val isRead = novelReadMarkerKey(sourceId, d.path, chapter.path) in readKeys
                            when (chapterReadFilter) {
                                "read" -> isRead
                                "unread" -> !isRead
                                else -> true
                            }
                        }
                        itemsIndexed(visibleChapters, key = { _, entry -> entry.second.path }) { visibleIndex, entry ->
                            val (index, chapter) = entry
                            val isRead = novelReadMarkerKey(sourceId, d.path, chapter.path) in readKeys
                            val accessKey = MslChapterAccess.novelKey(sourceId, d.path, chapter.path)
                            val chapterUnlocked = remember(chapterAccessRevision, chapter.path) {
                                !MslWallet.enabled ||
                                    accessKey?.let { MslChapterAccess.isUnlocked(context, it) } == true
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (selectedPaths.isNotEmpty()) {
                                                selectedPaths =
                                                    if (chapter.path in
                                                        selectedPaths
                                                    ) {
                                                        selectedPaths - chapter.path
                                                    } else {
                                                        selectedPaths +
                                                            chapter.path
                                                    }
                                            } else {
                                                openChapter(
                                                    visibleChapters.map {
                                                        it.second
                                                    },
                                                    visibleIndex,
                                                    visibleChapters.map { it.first },
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            selectedPaths =
                                                if (chapter.path in
                                                    selectedPaths
                                                ) {
                                                    selectedPaths - chapter.path
                                                } else {
                                                    selectedPaths +
                                                        chapter.path
                                                }
                                        },
                                    ),
                                shape = NovelColors.compactCardShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (chapter.path in
                                        selectedPaths
                                    ) {
                                        NovelColors.accent.copy(alpha = 0.10f)
                                    } else {
                                        NovelColors.surface
                                    },
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isRead) NovelColors.accent.copy(alpha = 0.24f) else NovelColors.border,
                                ),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            chapter.name,
                                            color = if (isRead) NovelColors.textMuted else NovelColors.textPrimary,
                                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                                        )
                                        chapter.releaseTime?.takeIf(String::isNotBlank)?.let {
                                            Text(
                                                it,
                                                color = NovelColors.textMuted,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    if (isRead) {
                                        Text(
                                            "مقروء ✓",
                                            color = NovelColors.accent,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    if (!chapterUnlocked) {
                                        IconButton(
                                            onClick = {
                                                val key = accessKey ?: return@IconButton
                                                detailsScope.launch {
                                                    val result =
                                                        withContext(Dispatchers.IO) {
                                                            MslChapterAccess.unlock(context, listOf(key))
                                                        }
                                                    if (result?.allowed == true) {
                                                        chapterAccessRevision++
                                                        Toast.makeText(
                                                            context,
                                                            "تم فتح الفصل مقابل ${result.charged} عملة",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    } else if (result?.status == "insufficient") {
                                                        Toast.makeText(
                                                            context,
                                                            "الرصيد غير كافٍ. المطلوب ${result.required} عملة.",
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "تعذّر التحقق من المحفظة. تحقق من الاتصال.",
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(48.dp).semantics {
                                                contentDescription =
                                                    "فتح ${chapter.name}"
                                            },
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_chapter_lock_24dp),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .semantics {
                                                contentDescription =
                                                    if (chapter.path in
                                                        downloadedPaths
                                                    ) {
                                                        "${chapter.name} محمّل"
                                                    } else {
                                                        "تحميل ${chapter.name}"
                                                    }
                                            }
                                            .clickable(
                                                enabled =
                                                chapter.path in downloadedPaths || chapter.path in downloadingPaths ||
                                                    MslWallet.enabled,
                                                onClick = { requestDownload(index, chapter) },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (chapter.path in
                                                downloadedPaths
                                            ) {
                                                "✓"
                                            } else if (chapter.path in
                                                downloadingPaths
                                            ) {
                                                "…"
                                            } else {
                                                "⇩"
                                            },
                                            color = NovelColors.accent,
                                            fontSize = 21.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showNovelComments) {
            MslCommentsDialog(
                title = details?.name ?: fallbackName,
                onDismiss = { showNovelComments = false },
            )
        }
        if (showDownloadAllConfirm) {
            val chapterCount = details?.chapters.orEmpty().size
            AlertDialog(
                onDismissRequest = { showDownloadAllConfirm = false },
                containerColor = NovelColors.surface,
                title = { Text("تحميل جميع الفصول؟", color = NovelColors.textPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "سيتم التحقق من إمكانية فتح $chapterCount فصلًا وتحميلها للقراءة بلا إنترنت. قد تُستهلك عملات للفصول المقفلة، وبحد أقصى 100 فصل في العملية الواحدة.",
                        color = NovelColors.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDownloadAllConfirm = false
                            startBulkDownload(
                                details?.chapters.orEmpty().mapIndexed { index, chapter ->
                                    index to
                                        chapter
                                },
                            )
                        },
                    ) { Text("متابعة", color = NovelColors.accent) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDownloadAllConfirm = false
                    }) { Text("إلغاء", color = NovelColors.textSecondary) }
                },
            )
        }
    }
}

private suspend fun saveNovelOfflineChapter(
    context: android.content.Context,
    manager: NovelSourceManager,
    store: NovelOfflineStore,
    sourceId: String,
    details: NovelDetails,
    fallbackName: String,
    index: Int,
    chapter: NovelChapter,
): String? {
    if (!MslWallet.enabled) return "تنزيل الفصول غير متاح حاليًا."
    if (store.contains(sourceId, details.path, chapter.path)) return null
    val key = MslChapterAccess.novelKey(sourceId, details.path, chapter.path)
        ?: return "تعذّر إنشاء هوية ثابتة للفصل."
    val unlock = try {
        withContext(Dispatchers.IO) {
            MslChapterAccess.unlock(context, listOf(key))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return failure.message ?: "تعذّر التحقق من صلاحية الفصل."
    }
    if (unlock == null) {
        return "تعذّر التحقق من صلاحية الفصل. تحقق من الاتصال ثم أعد المحاولة."
    }
    if (!unlock.allowed) return "الرصيد غير كافٍ. المطلوب ${unlock.required} عملة."
    val saved = try {
        val content = withContext(Dispatchers.IO) {
            manager.ensureLoaded()
            manager.chapter(sourceId, chapter.path)
        }
        withContext(Dispatchers.IO) {
            store.save(
                sourceId = sourceId,
                novelPath = details.path,
                chapterPath = chapter.path,
                html = content,
                title = details.name ?: fallbackName,
                cover = details.cover,
                description = details.summary,
                chapterTitle = chapter.name,
                chapterNumber = chapter.chapterNumber,
                order = index,
            )
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
    if (saved.isFailure) {
        return saved.exceptionOrNull()?.message ?: "تعذّر تحميل الفصل."
    }
    return null
}

class NovelReaderScreen(
    private val sourceId: String,
    private val novelPath: String,
    private val novelName: String,
    private val novelCover: String?,
    private val chapters: List<NovelChapter>,
    private val chapterIndex: Int,
    private val offlineOnly: Boolean = false,
    private val novelDescription: String? = null,
    private val sourceChapterIndices: List<Int> = chapters.indices.toList(),
) : Screen() {

    @Composable
    override fun Content() {
        NovelTheme { ReaderContent() }
    }

    @Composable
    private fun ReaderContent() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val historyStore = remember { NovelHistoryStore(context) }
        val readStore = remember { NovelReadStore(context) }
        val offlineStore = remember { NovelOfflineStore(context) }
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val readerPrefs = remember {
            context.getSharedPreferences("manhwa_slime_reader_prefs", android.content.Context.MODE_PRIVATE)
        }
        val isDarkTheme = isSystemInDarkTheme()
        var fontSize by remember {
            mutableFloatStateOf(readerPrefs.getFloat("font_size", 20f).coerceIn(14f, 32f))
        }
        var lineSpacing by remember {
            mutableFloatStateOf(readerPrefs.getFloat("line_spacing", 2.05f).coerceIn(1.2f, 3f))
        }
        var fontFamily by remember {
            mutableStateOf(readerPrefs.getString("font_family", "default") ?: "default")
        }
        var pageTheme by remember {
            mutableStateOf(
                readerPrefs.getString("page_theme", if (isDarkTheme) "night" else "paper") ?: "paper",
            )
        }
        var textAlignment by remember {
            mutableStateOf(readerPrefs.getString("text_alignment", "right") ?: "right")
        }
        var readingMargin by remember {
            mutableFloatStateOf(readerPrefs.getFloat("reading_margin", 20f).coerceIn(8f, 40f))
        }
        var showReaderSettings by remember { mutableStateOf(false) }
        var fullscreen by remember { mutableStateOf(false) }
        var chromeVisible by remember { mutableStateOf(true) }
        var retryRevision by remember { mutableIntStateOf(0) }
        var scrollProgress by remember { mutableFloatStateOf(0f) }
        val prefetchedContent = remember { mutableStateMapOf<String, String>() }

        DisposableEffect(Unit) {
            val window = context.findActivity()?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        DisposableEffect(fullscreen) {
            val decor = context.findActivity()?.window?.decorView
            decor?.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
            onDispose {
                decor?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }

        var currentIndex by remember {
            mutableIntStateOf(
                chapterIndex.coerceIn(
                    0,
                    (chapters.size - 1).coerceAtLeast(0),
                ),
            )
        }

        val currentScrollKey = remember(sourceId, novelPath, currentIndex) {
            "reader_scroll:$sourceId:$novelPath:${chapters.getOrNull(currentIndex)?.path.orEmpty()}"
        }

        var html by remember { mutableStateOf<String?>(null) }
        var readerWebView by remember { mutableStateOf<WebView?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var locked by remember { mutableStateOf(false) }
        var accessVersion by remember { mutableIntStateOf(0) }
        var offlineSaved by remember { mutableStateOf(false) }
        var offlineSaving by remember { mutableStateOf(false) }

        DisposableEffect(currentScrollKey) {
            val key = currentScrollKey
            onDispose {
                readerWebView?.let { readerPrefs.edit().putInt(key, it.scrollY).apply() }
            }
        }

        LaunchedEffect(currentScrollKey, loading) {
            if (loading) return@LaunchedEffect
            while (true) {
                delay(2_000)
                readerWebView?.let { webView ->
                    readerPrefs.edit().putInt(currentScrollKey, webView.scrollY).apply()
                    val range = webView.scrollableRange()
                    scrollProgress = (webView.scrollY.toFloat() / range).coerceIn(0f, 1f)
                }
            }
        }

        LaunchedEffect(currentIndex, sourceId, novelPath) {
            val nextChapter = chapters.getOrNull(currentIndex + 1) ?: return@LaunchedEffect
            if (offlineOnly || nextChapter.path in prefetchedContent) return@LaunchedEffect
            val cached = withContext(Dispatchers.IO) { offlineStore.read(sourceId, novelPath, nextChapter.path) }
            if (!cached.isNullOrBlank()) {
                prefetchedContent[nextChapter.path] = cached
                return@LaunchedEffect
            }
            val accessKey = MslChapterAccess.novelKey(sourceId, novelPath, nextChapter.path)
            val hasAccess = withContext(Dispatchers.IO) { MslChapterAccess.hasAccess(context, accessKey) }
            if (!hasAccess) return@LaunchedEffect
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    manager.ensureLoaded()
                    manager.chapter(sourceId, nextChapter.path)
                }
            }
            result.getOrNull()?.let { prefetchedContent[nextChapter.path] = it }
        }

        LaunchedEffect(
            currentIndex,
            sourceId,
            novelPath,
            offlineOnly,
            accessVersion,
            retryRevision,
            chapters.getOrNull(currentIndex)?.path,
        ) {
            val chapter = chapters.getOrNull(currentIndex)

            if (chapter == null) {
                error = context.stringResource(MR.strings.coin_chapter_missing)
                loading = false
                return@LaunchedEffect
            }

            loading = true
            html = null
            error = null
            locked = false
            val cachedContent = runCatchingCancellable {
                withContext(Dispatchers.IO) { offlineStore.read(sourceId, novelPath, chapter.path) }
            }.getOrNull()
            offlineSaved = !cachedContent.isNullOrBlank()

            if (offlineOnly && !offlineSaved) {
                error = "هذا الفصل غير محفوظ على الجهاز أو تعذّرت قراءة ملفه."
                loading = false
                return@LaunchedEffect
            }
            if (!offlineSaved) {
                val accessKey = MslChapterAccess.novelKey(sourceId, novelPath, chapter.path)
                if (!withContext(Dispatchers.IO) { MslChapterAccess.hasAccess(context, accessKey) }) {
                    locked = true
                    loading = false
                    return@LaunchedEffect
                }
            }

            val result = runCatchingCancellable {
                if (offlineSaved) {
                    cachedContent.orEmpty()
                } else {
                    prefetchedContent.remove(chapter.path) ?: withContext(Dispatchers.IO) {
                        manager.ensureLoaded()
                        manager.chapter(sourceId, chapter.path)
                    }
                }
            }

            result.onSuccess { content ->
                html = content

                withContext(Dispatchers.IO) {
                    historyStore.upsert(
                        NovelHistoryEntry(
                            sourceId = sourceId,
                            novelPath = novelPath,
                            novelTitle = novelName,
                            cover = novelCover,
                            chapterPath = chapter.path,
                            chapterName = chapter.name,
                            chapterNumber = chapter.chapterNumber,
                            chapterIndex = sourceChapterIndices.getOrElse(currentIndex) { currentIndex },
                            totalChapters = chapters.size,
                        ),
                    )
                }
            }

            result.onFailure {
                error = it.message ?: context.stringResource(MR.strings.coin_chapter_load_failed)
            }

            loading = false
        }

        val hasPrevious = currentIndex > 0
        val hasNext = currentIndex < chapters.lastIndex
        var currentChapterMarkedRead by remember(currentIndex) { mutableStateOf(false) }
        fun markCurrentChapterRead() {
            if (locked || html == null) return
            if (currentChapterMarkedRead) return
            val chapter = chapters.getOrNull(currentIndex) ?: return
            currentChapterMarkedRead = true
            scope.launch(Dispatchers.IO) { readStore.markRead(sourceId, novelPath, chapter.path) }
        }

        Scaffold(
            topBar = {
                if (!fullscreen && chromeVisible) {
                    NovelToolbar(
                        title = {
                            Column {
                                Text(
                                    text = novelName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                Text(
                                    text = chapters
                                        .getOrNull(currentIndex)
                                        ?.name
                                        ?: "الفصل ${currentIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { navigator.pop() },
                            ) {
                                Text("‹", fontSize = 32.sp)
                            }
                        },
                        actions = {
                            TextButton(
                                enabled =
                                MslWallet.enabled && html != null && !loading && !offlineSaving && !offlineSaved,
                                onClick = {
                                    val chapter = chapters.getOrNull(currentIndex) ?: return@TextButton
                                    val content = html ?: return@TextButton
                                    scope.launch {
                                        offlineSaving = true
                                        try {
                                            val key = MslChapterAccess.novelKey(sourceId, novelPath, chapter.path)
                                            val hasAccess = withContext(Dispatchers.IO) {
                                                MslChapterAccess.hasAccess(context, key)
                                            }
                                            if (!hasAccess) {
                                                snackbarHostState.showSnackbar(
                                                    "الفصل مقفل أو تعذّر التحقق من صلاحية القراءة.",
                                                )
                                                return@launch
                                            }
                                            val saved = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    offlineStore.save(
                                                        sourceId = sourceId,
                                                        novelPath = novelPath,
                                                        chapterPath = chapter.path,
                                                        html = content,
                                                        title = novelName,
                                                        cover = novelCover,
                                                        description = novelDescription,
                                                        chapterTitle = chapter.name,
                                                        chapterNumber = chapter.chapterNumber,
                                                        order = currentIndex,
                                                    )
                                                }
                                            }
                                            if (saved.isFailure) {
                                                snackbarHostState.showSnackbar(
                                                    context.stringResource(MR.strings.coin_download_save_failed),
                                                )
                                                return@launch
                                            }
                                            offlineSaved = true
                                            snackbarHostState.showSnackbar(
                                                context.stringResource(MR.strings.coin_download_saved_message),
                                            )
                                        } finally {
                                            offlineSaving = false
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    when {
                                        offlineSaved -> stringResource(MR.strings.coin_download_saved_label)
                                        offlineSaving -> stringResource(MR.strings.coin_download_saving)
                                        else -> stringResource(MR.strings.coin_download_button)
                                    },
                                )
                            }
                            TextButton(onClick = { showReaderSettings = true }) {
                                Text("Aa", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = {
                                context.findActivity()?.let { MslTranslate.runForView(it, readerWebView) }
                            }) {
                                Text("ع", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { fullscreen = !fullscreen }) {
                                Text(if (fullscreen) "⤢" else "⛶", fontWeight = FontWeight.Bold)
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!fullscreen && chromeVisible) {
                    Surface(
                        tonalElevation = 4.dp,
                    ) {
                        Column {
                            LinearProgressIndicator(
                                progress = scrollProgress,
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = NovelColors.accent,
                                trackColor = NovelColors.divider,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = hasPrevious && !loading,
                                    onClick = {
                                        if (hasPrevious) {
                                            currentIndex--
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.coin_reader_previous))
                                }

                                Text(
                                    text = "${currentIndex + 1}/${chapters.size}",
                                    fontWeight = FontWeight.Bold,
                                )
                                TextButton(enabled = html != null && !locked, onClick = { markCurrentChapterRead() }) {
                                    Text(
                                        if (currentChapterMarkedRead) "مقروء ✓" else "تمت القراءة",
                                        color = if (currentChapterMarkedRead) NovelColors.accentBright else NovelColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }

                                Button(
                                    modifier = Modifier.weight(1f),
                                    enabled = hasNext && !loading && html != null,
                                    onClick = {
                                        if (hasNext) {
                                            markCurrentChapterRead()
                                            if (!offlineSaved) {
                                                val chapter = chapters.getOrNull(currentIndex)
                                                if (chapter != null) {
                                                    MslAds.recordOnlineChapterCompleted(
                                                        context,
                                                        "novel:$sourceId:$novelPath:${chapter.path}",
                                                    )
                                                    context.findActivity()?.let { activity ->
                                                        MslAds.showPendingInterstitialAtChapterBreak(activity)
                                                    }
                                                }
                                            }
                                            currentIndex++
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.coin_reader_next))
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        CircularProgressIndicator()
                    }
                }

                locked -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .wrapContentSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("هذا الفصل مقفل", style = MaterialTheme.typography.titleLarge)
                        Text("فتح الفصل يكلف عملة واحدة. لا يُحمّل النص قبل نجاح العملية.")
                        Button(
                            onClick = {
                                val chapter = chapters.getOrNull(currentIndex) ?: return@Button
                                val key = MslChapterAccess.novelKey(sourceId, novelPath, chapter.path)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        MslChapterAccess.unlock(context, listOfNotNull(key))
                                    }
                                    if (result?.allowed == true) {
                                        accessVersion++
                                    } else if (result?.status == "insufficient") {
                                        snackbarHostState.showSnackbar(
                                            "الرصيد غير كافٍ. الرصيد: ${result.balance ?: 0}، المطلوب: ${result.required} عملة.",
                                        )
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            "تعذّر التحقق من المحفظة. تحقق من الاتصال ثم أعد المحاولة.",
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("فتح مقابل عملة واحدة")
                        }
                    }
                }

                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(text = error ?: "حدث خطأ", color = NovelColors.danger)
                            Button(onClick = { retryRevision++ }, shape = CircleShape) { Text("إعادة المحاولة") }
                        }
                    }
                }

                html != null -> {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                readerWebView = this
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String?) {
                                        view.post {
                                            val savedScroll = readerPrefs.getInt(currentScrollKey, 0)
                                            view.scrollTo(0, savedScroll)
                                            val range = view.scrollableRange()
                                            scrollProgress = (savedScroll.toFloat() / range).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.setSupportZoom(false)
                                settings.builtInZoomControls = false
                                settings.cacheMode =
                                    WebSettings.LOAD_DEFAULT
                                val detector = GestureDetector(
                                    ctx,
                                    object : GestureDetector.SimpleOnGestureListener() {
                                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                                            chromeVisible = !chromeVisible
                                            return true
                                        }

                                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                                            fullscreen = !fullscreen
                                            return true
                                        }
                                    },
                                )
                                setOnTouchListener { _, event ->
                                    detector.onTouchEvent(event)
                                    false
                                }
                            }
                        },
                        update = { webView ->
                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    view.post {
                                        val savedScroll = readerPrefs.getInt(currentScrollKey, 0)
                                        view.scrollTo(0, savedScroll)
                                        val range = view.scrollableRange()
                                        scrollProgress = (savedScroll.toFloat() / range).coerceIn(0f, 1f)
                                        if (!view.canScrollVertically(1) && !loading) markCurrentChapterRead()
                                    }
                                }
                            }
                            val (bg, fg, accent, quoteBg) = when (pageTheme) {
                                "white" -> listOf("#FFFFFF", "#1D1B2E", "#5B3DF5", "#F2F0FF")
                                "gray" -> listOf("#E7E7E7", "#242424", "#5B3DF5", "#DADADA")
                                "night" -> listOf("#17161C", "#EAE7F2", "#B5A5FF", "#24212C")
                                else -> listOf("#FBF8F3", "#2A2738", "#5B3DF5", "#F0EDE6")
                            }
                            val cssFont = when (fontFamily) {
                                "naskh" -> "MslNaskh"
                                "kufi" -> "MslKufi"
                                else -> "MslDefault"
                            }
                            webView.setOnScrollChangeListener { view, _, scrollY, _, _ ->
                                val range = webView.scrollableRange()
                                scrollProgress = (scrollY.toFloat() / range).coerceIn(0f, 1f)
                                if (scrollY > 0 && !view.canScrollVertically(1) && !loading) markCurrentChapterRead()
                            }

                            val style = """
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                        @font-face { font-family: 'MslDefault'; src: url('fonts/IBMPlexSansArabic-Regular.ttf'); }
                        @font-face { font-family: 'MslNaskh'; src: url('fonts/Amiri-Regular.ttf'); }
                        @font-face { font-family: 'MslKufi'; src: url('fonts/NotoKufiArabic.ttf'); }
                        * { box-sizing: border-box; }
                        html { background: $bg; }
                        body {
                            font-family: "$cssFont", sans-serif !important;
                            font-size: ${fontSize}px !important;
                            line-height: $lineSpacing !important;
                            padding: 26px ${readingMargin.toInt()}px 40px !important;
                            margin: 0;
                            background: $bg;
                            color: $fg;
                            overflow-wrap: anywhere;
                            direction: rtl;
                            text-align: $textAlignment !important;
                        }
                        body * { font-family: "$cssFont", sans-serif !important; }
                                h1, h2, h3 {
                                    line-height: 1.65;
                                    color: $accent;
                                    margin: 1.5em 0 .7em;
                                }
                                p { margin: 0 0 1.35em; }
                                img, video { max-width: 100%; height: auto; border-radius: 12px; }
                                a { color: $accent; }
                                blockquote {
                                    margin: 1.2em 0;
                                    padding: 8px 16px;
                                    border-inline-start: 3px solid $accent;
                                    background: $quoteBg;
                                    border-radius: 8px;
                                }
                                pre, code {
                                    white-space: pre-wrap;
                                    overflow-wrap: anywhere;
                                }
                                table { max-width: 100%; display: block; overflow-x: auto; }
                                </style>
                            """.trimIndent()

                            val sourceHtml = html.orEmpty()
                            val finalHtml = when {
                                sourceHtml.contains("</head>", ignoreCase = true) ->
                                    sourceHtml.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$style</head>")
                                sourceHtml.contains("<body", ignoreCase = true) -> {
                                    val openingBody = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(sourceHtml)
                                    if (openingBody == null) {
                                        "<html><head>$style</head><body>$sourceHtml</body></html>"
                                    } else {
                                        sourceHtml.replaceRange(openingBody.range, openingBody.value + style)
                                    }
                                }
                                sourceHtml.contains("<html", ignoreCase = true) ->
                                    sourceHtml.replaceFirst(
                                        Regex("<html[^>]*>", RegexOption.IGNORE_CASE),
                                        "<html><head>$style</head>",
                                    )
                                else -> "<!doctype html><html><head>$style</head><body>$sourceHtml</body></html>"
                            }
                            val renderKey = "$currentIndex|$fontSize|$lineSpacing|$fontFamily|$textAlignment|$readingMargin|$pageTheme"
                            if (webView.tag != renderKey) {
                                webView.tag = renderKey
                                webView.loadDataWithBaseURL(
                                    "file:///android_asset/",
                                    finalHtml,
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        },
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        Text("لا يوجد محتوى")
                    }
                }
            }

            if (showReaderSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showReaderSettings = false },
                    containerColor = NovelColors.surface,
                    contentColor = NovelColors.textPrimary,
                    shape = MaterialTheme.shapes.extraLarge,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "إعدادات القراءة",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("حجم الخط · ${fontSize.toInt()}", color = NovelColors.textSecondary)
                        Slider(
                            value = fontSize,
                            onValueChange = {
                                fontSize = it
                                readerPrefs.edit().putFloat("font_size", it).apply()
                            },
                            valueRange = 14f..32f,
                            steps = 17,
                        )
                        Text("لون الصفحة", color = NovelColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "white" to "أبيض",
                                "paper" to "ورقي",
                                "gray" to "رمادي",
                                "night" to "ليلي",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = pageTheme == value,
                                    onClick = {
                                        pageTheme = value
                                        readerPrefs.edit().putString("page_theme", value).apply()
                                    },
                                    label = { Text(label) },
                                    shape = CircleShape,
                                    colors = novelChipColors(),
                                )
                            }
                        }
                        Text("نوع الخط", color = NovelColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "default" to "افتراضي",
                                "naskh" to "نسخ",
                                "kufi" to "كوفي",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = fontFamily == value,
                                    onClick = {
                                        fontFamily = value
                                        readerPrefs.edit().putString("font_family", value).apply()
                                    },
                                    label = { Text(label) },
                                    shape = CircleShape,
                                    colors = novelChipColors(),
                                )
                            }
                        }
                        Text("تباعد الأسطر", color = NovelColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1.55f to "ضيق", 1.95f to "متوسط", 2.4f to "واسع").forEach { (value, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(lineSpacing - value) < 0.12f,
                                    onClick = {
                                        lineSpacing = value
                                        readerPrefs.edit().putFloat("line_spacing", value).apply()
                                    },
                                    label = { Text(label) },
                                    shape = CircleShape,
                                    colors = novelChipColors(),
                                )
                            }
                        }
                        Text("محاذاة النص", color = NovelColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("right" to "يمين", "center" to "وسط", "left" to "يسار").forEach { (value, label) ->
                                FilterChip(
                                    selected = textAlignment == value,
                                    onClick = {
                                        textAlignment = value
                                        readerPrefs.edit().putString("text_alignment", value).apply()
                                    },
                                    label = { Text(label) },
                                    shape = CircleShape,
                                    colors = novelChipColors(),
                                )
                            }
                        }
                        Text("هامش القراءة · ${readingMargin.toInt()} px", color = NovelColors.textSecondary)
                        Slider(
                            value = readingMargin,
                            onValueChange = {
                                readingMargin = it
                                readerPrefs.edit().putFloat("reading_margin", it).apply()
                            },
                            valueRange = 8f..40f,
                            steps = 7,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    fontSize = 20f
                                    lineSpacing = 1.95f
                                    fontFamily = "default"
                                    pageTheme = "paper"
                                    textAlignment = "right"
                                    readingMargin = 20f
                                    readerPrefs.edit()
                                        .putFloat("font_size", fontSize)
                                        .putFloat("line_spacing", lineSpacing)
                                        .putString("font_family", fontFamily)
                                        .putString("page_theme", pageTheme)
                                        .putString("text_alignment", textAlignment)
                                        .putFloat("reading_margin", readingMargin)
                                        .apply()
                                },
                            ) { Text("إعادة الضبط", color = NovelColors.textSecondary) }
                            TextButton(onClick = {
                                showReaderSettings = false
                            }) { Text("تم", color = NovelColors.accent) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
