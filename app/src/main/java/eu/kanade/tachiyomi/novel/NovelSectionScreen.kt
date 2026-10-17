@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.GestureDetector
import android.view.View
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.mslime.MslAds
import eu.kanade.tachiyomi.mslime.MslCommentsDialog
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.MslFavoriteItem
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavorites
import eu.kanade.tachiyomi.mslime.MslWallet
import eu.kanade.tachiyomi.mslime.MslTranslate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
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
    val loading: Boolean = true,
    val query: String = "",
    val sortMode: String = "new",
    val layoutMode: String = "grid",
    val repoDialog: Boolean = false,
    val sourcesDialog: Boolean = false,
    val repoUrl: String = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
    val repoEntries: List<NovelRepositoryEntry> = emptyList(),
    val sourceLanguageFilter: String = "supported",
    val catalogLoading: Boolean = false,
    val inputMode: NovelInputMode = NovelInputMode.REPOSITORY,
    val showFavorites: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

private enum class NovelInputMode { REPOSITORY, DIRECT_URL }

class NovelSectionScreen : Screen() {
    @Composable
    override fun Content() {
        NovelSectionContent()
    }
}

@Composable
fun NovelSectionContent() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val manager = remember { NovelManagerHolder.get(context) }
    val uiPrefs = remember { context.getSharedPreferences("msl_novel_ui", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf(
            NovelUiState(
                selectedSource = uiPrefs.getString("last_source", null),
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
            if (querySnapshot.isNotEmpty()) delay(350)
            // Load installed JS plugins once instead of reinitializing every source change.
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
                error = null,
                info = null,
            )

            val targets = if (allSources) sources else sources.filter { it.id == sid }
            if (targets.isEmpty()) {
                state = state.copy(novels = emptyList(), loading = false)
                return@launch
            }

            val sortMode = state.sortMode
            val responses = targets.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        val result = when {
                            querySnapshot.isNotBlank() -> manager.search(source.id, querySnapshot)
                            sortMode == "popular" -> manager.popular(source.id)
                            else -> manager.latest(source.id)
                        }
                        result.map { it.copy(sourceId = source.id, sourceName = source.name) } to null
                    }.getOrElse { emptyList<NovelItem>() to (it.message ?: "تعذر تحميل ${source.name}.") }
                }
            }.awaitAll()
            val novels = responses.flatMap { it.first }
                .filterNot { it.isAdultContent() }
                .distinctBy { "${it.sourceId}:${it.path}" }
                .let { items -> if (sortMode == "rating") items.sortedByDescending { it.ratingValue() ?: -1.0 } else items }
            if (state.query.trim() == querySnapshot && state.selectedSource == sid && state.allSources == allSources) {
                state = state.copy(
                    novels = novels,
                    loading = false,
                    error = if (novels.isEmpty()) responses.mapNotNull { it.second }.firstOrNull() else null,
                )
            }
        }
    }

    LaunchedEffect(Unit) { reload(state.selectedSource, allSources = false) }

    val installEntry: (NovelRepositoryEntry) -> Unit = { entry ->
        scope.launch {
            val result = runCatching { manager.install(entry, entry.repositoryUrl ?: state.repoUrl.trim()) }
            if (result.isFailure) {
                state = state.copy(error = result.exceptionOrNull()?.message)
            } else {
                val installed = result.getOrThrow()
                state = state.copy(repoDialog = false, sourcesDialog = false, info = "تم تثبيت ${installed.name}")
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
        onToggleFavoriteFilter = {
            state = state.copy(showFavorites = !state.showFavorites)
        },
        onShowSources = {
            state = state.copy(sourcesDialog = true, sourceLanguageFilter = "supported", catalogLoading = true, error = null, info = null)
            scope.launch {
                val result = runCatching { manager.loadRepositories() }
                state = state.copy(
                    repoEntries = result.getOrDefault(emptyList()),
                    catalogLoading = false,
                    error = result.exceptionOrNull()?.message,
                    info = result.getOrNull()?.let { "تم تحميل ${it.size} مصدرًا من فهارس LNReader" },
                )
            }
        },
        onSearch = { q ->
            state = state.copy(query = q, info = null, loading = true, error = null)
            reload(state.selectedSource, state.allSources)
        },
        onSelectSource = { sid ->
            uiPrefs.edit().putString("last_source", sid).apply()
            reload(sid, allSources = false)
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
        onAddRepository = { state = state.copy(repoDialog = true, error = null, info = null) },
        onInputModeChange = {
            state = state.copy(inputMode = it, repoEntries = emptyList(), error = null, info = null)
        },
        onRepoUrlChange = { state = state.copy(repoUrl = it, error = null, info = null) },
        onInstall = installEntry,
        onInstallDirect = {
            scope.launch {
                val url = state.repoUrl.trim()
                val r = runCatching { manager.installFromUrl(url, repositoryUrl = null) }
                if (r.isFailure) {
                    state = state.copy(error = r.exceptionOrNull()?.message)
                } else {
                    state = state.copy(repoDialog = false, info = "تم تثبيت ${r.getOrThrow().name}")
                    reload(r.getOrThrow().id)
                }
            }
        },
        onOpen = { item ->
            val sid = item.sourceId ?: state.selectedSource
            if (sid != null) navigator.push(NovelDetailsScreen(sid, item.path, item.name))
        },
        onCloseRepo = { state = state.copy(repoDialog = false, error = null) },
        onLoadRepo = {
            state = state.copy(catalogLoading = true)
            scope.launch {
                val result = runCatching { manager.loadRepository(state.repoUrl) }
                state = state.copy(
                    repoEntries = result.getOrDefault(emptyList()),
                    catalogLoading = false,
                    error = result.exceptionOrNull()?.message,
                    info = result.getOrNull()?.let { "تم العثور على ${it.size} مصدرًا" },
                )
            }
        },
    )
    if (state.sourcesDialog) {
        NovelSourcesCatalogDialog(
            entries = state.repoEntries,
            installedIds = state.sources.map { it.id }.toSet(),
            languageFilter = state.sourceLanguageFilter,
            loading = state.catalogLoading,
            error = state.error,
            onLanguageFilter = { state = state.copy(sourceLanguageFilter = it) },
            onInstall = installEntry,
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
    onAddRepository: () -> Unit,
    onInputModeChange: (NovelInputMode) -> Unit,
    onRepoUrlChange: (String) -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onInstallDirect: () -> Unit,
    onOpen: (NovelItem) -> Unit,
    onCloseRepo: () -> Unit,
    onLoadRepo: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MslDesignTokens.background),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(MslDesignTokens.backgroundRaised, Color(0xFF160E2A), MslDesignTokens.backgroundRaised),
                            ),
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "الروايات",
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MslDesignTokens.textPrimary,
                            )
                            Surface(shape = MslDesignTokens.pillShape, color = Color(0xFF2A1F5E)) {
                                Text("قيد التطوير", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color(0xFFD5C8FF), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("اكتشف قراءتك القادمة", style = MaterialTheme.typography.bodyMedium, color = MslDesignTokens.textSecondary)
                    }
                    FilledTonalIconButton(
                        onClick = onAddRepository,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "إضافة مصدر" },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MslDesignTokens.accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("+", fontSize = 24.sp, fontWeight = FontWeight.Light)
                    }
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = MslDesignTokens.pillShape,
                    placeholder = {
                        Text("ابحث عن رواية أو مؤلف...", color = MslDesignTokens.textMuted)
                    },
                    leadingIcon = { Text("⌕", color = MslDesignTokens.accentBright, fontSize = 24.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MslDesignTokens.textPrimary,
                        unfocusedTextColor = MslDesignTokens.textPrimary,
                        focusedContainerColor = MslDesignTokens.surface,
                        unfocusedContainerColor = MslDesignTokens.surface,
                        focusedBorderColor = MslDesignTokens.accentBright,
                        unfocusedBorderColor = MslDesignTokens.border,
                        cursorColor = MslDesignTokens.accentBright,
                    ),
                )

                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onShowSources),
                    shape = MslDesignTokens.cardShape,
                    colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                    border = BorderStroke(1.dp, MslDesignTokens.border),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("المصادر · ${state.sources.size} مثبتة", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                "المصادر العربية والإنجليزية المثبتة في LNReader",
                                color = MslDesignTokens.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onShowSources) {
                            Text("استعراض", color = MslDesignTokens.accentBright)
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.allSources,
                            onClick = onSelectAllSources,
                            label = { Text("بحث شامل") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MslDesignTokens.surface,
                                labelColor = MslDesignTokens.textSecondary,
                                selectedContainerColor = MslDesignTokens.accent,
                                selectedLabelColor = MslDesignTokens.textPrimary,
                            ),
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.showFavorites,
                            onClick = onToggleFavoriteFilter,
                            label = { Text("المفضلة") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MslDesignTokens.surfaceRaised,
                                labelColor = MslDesignTokens.textSecondary,
                                selectedContainerColor = MslDesignTokens.accent.copy(alpha = 0.28f),
                                selectedLabelColor = MslDesignTokens.textPrimary,
                            ),
                        )
                    }
                    items(state.sources) { source ->
                        FilterChip(
                            selected = !state.allSources && state.selectedSource == source.id,
                            onClick = { onSelectSource(source.id) },
                            label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MslDesignTokens.surfaceRaised,
                                labelColor = MslDesignTokens.textSecondary,
                                selectedContainerColor = MslDesignTokens.accent,
                                selectedLabelColor = MslDesignTokens.textPrimary,
                            ),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("new" to "أحدث", "popular" to "أشهر", "rating" to "أعلى تقييمًا")) { option ->
                            FilterChip(
                                selected = state.sortMode == option.first,
                                onClick = { onSelectSort(option.first) },
                                label = { Text(option.second, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MslDesignTokens.surface,
                                    labelColor = MslDesignTokens.textSecondary,
                                    selectedContainerColor = MslDesignTokens.accent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleLayout,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = if (state.layoutMode == "grid") "التبديل إلى عرض القائمة" else "التبديل إلى عرض الشبكة"
                        },
                    ) {
                        Text(if (state.layoutMode == "grid") "☰" else "▦", color = MslDesignTokens.accentBright, fontSize = 23.sp)
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.info?.let {
                        Text(it, color = MslDesignTokens.success, style = MaterialTheme.typography.bodySmall)
                    }
                    state.error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it, modifier = Modifier.weight(1f), color = MslDesignTokens.danger, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onRetry) { Text("إعادة المحاولة", color = MslDesignTokens.accentBright) }
                        }
                    }
                }

                when {
                    state.loading -> NovelSkeletonResults(state.layoutMode)
                    state.sources.isEmpty() -> EmptyNovelSources(onAddRepository)
                    else -> {
                        val visibleNovels = if (state.showFavorites) {
                            state.novels.filter { item ->
                                val resultSource = item.sourceId ?: state.selectedSource
                                resultSource != null && "$resultSource::${item.path}" in favoriteKeys
                            }
                        } else {
                            state.novels
                        }

                        if (state.showFavorites && visibleNovels.isEmpty()) {
                            EmptyFavorites()
                        } else if (visibleNovels.isEmpty()) {
                            EmptyNovelResults(state.query, state.error != null, onRetry)
                        } else if (state.layoutMode == "grid") {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(130.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(visibleNovels, key = { "${it.sourceId}:${it.path}" }) { item ->
                                    NovelCard(
                                        item = item,
                                        onOpen = onOpen,
                                        isFavorite = (item.sourceId ?: state.selectedSource)?.let { resultSource ->
                                            "$resultSource::${item.path}" in favoriteKeys
                                        } == true,
                                        onToggleFavorite = onToggleFavorite,
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(visibleNovels, key = { "${it.sourceId}:${it.path}" }) { item ->
                                    NovelListRow(
                                        item = item,
                                        onOpen = onOpen,
                                        isFavorite = (item.sourceId ?: state.selectedSource)?.let { resultSource ->
                                            "$resultSource::${item.path}" in favoriteKeys
                                        } == true,
                                        onToggleFavorite = onToggleFavorite,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.repoDialog) {
                AlertDialog(
                    onDismissRequest = onCloseRepo,
                    shape = MslDesignTokens.cardShape,
                    containerColor = MslDesignTokens.surface,
                    titleContentColor = MslDesignTokens.textPrimary,
                    textContentColor = MslDesignTokens.textSecondary,
                    title = { Text("إضافة مصادر الروايات", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "اختر مستودع LNReader أو ألصق رابط plugin.js مباشر.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FilterChip(
                                    selected = state.inputMode == NovelInputMode.REPOSITORY,
                                    onClick = { onInputModeChange(NovelInputMode.REPOSITORY) },
                                    label = { Text("مستودع") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MslDesignTokens.surfaceRaised,
                                        labelColor = MslDesignTokens.textSecondary,
                                        selectedContainerColor = MslDesignTokens.accent.copy(alpha = 0.28f),
                                        selectedLabelColor = MslDesignTokens.textPrimary,
                                    ),
                                )
                                FilterChip(
                                    selected = state.inputMode == NovelInputMode.DIRECT_URL,
                                    onClick = { onInputModeChange(NovelInputMode.DIRECT_URL) },
                                    label = { Text("رابط مباشر") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MslDesignTokens.surfaceRaised,
                                        labelColor = MslDesignTokens.textSecondary,
                                        selectedContainerColor = MslDesignTokens.accent.copy(alpha = 0.28f),
                                        selectedLabelColor = MslDesignTokens.textPrimary,
                                    ),
                                )
                            }
                            OutlinedTextField(
                                value = state.repoUrl,
                                onValueChange = onRepoUrlChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("URL") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MslDesignTokens.textPrimary,
                                    unfocusedTextColor = MslDesignTokens.textPrimary,
                                    focusedBorderColor = MslDesignTokens.accentBright,
                                    unfocusedBorderColor = MslDesignTokens.border,
                                    focusedLabelColor = MslDesignTokens.accentBright,
                                    unfocusedLabelColor = MslDesignTokens.textMuted,
                                ),
                            )
                            if (state.inputMode == NovelInputMode.REPOSITORY) {
                                Button(
                                    onClick = onLoadRepo,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MslDesignTokens.accent,
                                        contentColor = MslDesignTokens.textPrimary,
                                    ),
                                ) { Text("تحميل المستودع") }
                                val supportedRepoEntries = state.repoEntries.filter { it.lang.toNovelLanguageCode() != null }
                                if (state.repoEntries.isNotEmpty()) {
                                    HorizontalDivider(color = MslDesignTokens.border)
                                    Text(
                                        "المصادر العربية والإنجليزية: ${supportedRepoEntries.size}",
                                        fontWeight = FontWeight.SemiBold,
                                        color = MslDesignTokens.textPrimary,
                                    )
                                    if (supportedRepoEntries.isEmpty()) {
                                        Text("لم يعثر الفهرس على مصادر عربية أو إنجليزية.", color = MslDesignTokens.textMuted)
                                    } else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                        items(supportedRepoEntries) { entry ->
                                            ListItem(
                                                headlineContent = {
                                                    Text(entry.name, color = MslDesignTokens.textPrimary)
                                                },
                                                supportingContent = {
                                                    Text(
                                                        listOfNotNull(entry.lang, entry.version).joinToString(" • "),
                                                        color = MslDesignTokens.textMuted,
                                                    )
                                                },
                                                trailingContent = {
                                                    Button(
                                                        onClick = { onInstall(entry) },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = MslDesignTokens.surfaceHighest,
                                                            contentColor = MslDesignTokens.accentBright,
                                                        ),
                                                    ) { Text("تثبيت") }
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Button(
                                    onClick = onInstallDirect,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MslDesignTokens.accent,
                                        contentColor = MslDesignTokens.textPrimary,
                                    ),
                                ) { Text("تنزيل وتثبيت المصدر") }
                            }
                            Text(
                                "تنبيه: المصدر يشغّل JavaScript تابعًا لجهة خارجية داخل محرك المصادر. ثبّت الروابط التي تثق بها فقط.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MslDesignTokens.textMuted,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = onCloseRepo,
                            colors = ButtonDefaults.textButtonColors(contentColor = MslDesignTokens.accentBright),
                        ) { Text("إغلاق") }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyNovelSources(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "لا توجد مصادر روايات مثبتة",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MslDesignTokens.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text("أضف مستودعًا ثم ثبّت المصادر التي تريدها.", color = MslDesignTokens.textSecondary)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(
                containerColor = MslDesignTokens.accent,
                contentColor = MslDesignTokens.textPrimary,
            ),
        ) { Text("إضافة مستودع") }
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
        modifier = Modifier.fillMaxWidth(),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        border = BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(214.dp)
                .clip(MslDesignTokens.cardShape)
                .clickable { onOpen(item) },
        ) {
            NovelCoverSkeleton(Modifier.fillMaxSize())
            item.cover?.takeIf(String::isNotBlank)?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.14f), Color.Black.copy(alpha = 0.92f))),
                ),
            )
            item.sourceName?.takeIf(String::isNotBlank)?.let { name ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    shape = MslDesignTokens.pillShape,
                    color = Color.Black.copy(alpha = 0.58f),
                ) {
                    Text(name, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            IconButton(
                onClick = { onToggleFavorite(item) },
                modifier = Modifier.align(Alignment.TopStart).size(48.dp).semantics {
                    contentDescription = if (isFavorite) "إزالة ${item.name} من المفضلة" else "إضافة ${item.name} إلى المفضلة"
                },
            ) {
                Text("★", color = if (isFavorite) Color(0xFFFBBF24) else Color.White, fontSize = 23.sp)
            }
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.ratingValue()?.let { "رواية · ★ ${"%.1f".format(it)}" } ?: "رواية",
                    color = Color(0xFFC9CDE0),
                    style = MaterialTheme.typography.labelSmall,
                )
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
        modifier = Modifier.fillMaxWidth(),
        shape = MslDesignTokens.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        border = BorderStroke(1.dp, MslDesignTokens.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(10.dp))) {
                NovelCoverSkeleton(Modifier.fillMaxSize())
                item.cover?.takeIf(String::isNotBlank)?.let { cover ->
                    AsyncImage(model = cover, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, color = MslDesignTokens.textPrimary, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.sourceName ?: "مصدر روايات", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.ratingValue()?.let { Text("التقييم ${"%.1f".format(it)}", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelSmall) }
            }
            IconButton(
                onClick = { onToggleFavorite(item) },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = if (isFavorite) "إزالة ${item.name} من المفضلة" else "إضافة ${item.name} إلى المفضلة"
                },
            ) {
                Text("★", color = if (isFavorite) Color(0xFFFBBF24) else MslDesignTokens.textMuted, fontSize = 23.sp)
            }
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
        colors = listOf(MslDesignTokens.surfaceRaised, MslDesignTokens.border.copy(alpha = 0.75f), MslDesignTokens.surfaceRaised),
        start = Offset(shift, 0f),
        end = Offset(shift + 360f, 360f),
    )
    Box(modifier.background(brush))
}

@Composable
private fun NovelSkeletonResults(layoutMode: String) {
    if (layoutMode == "grid") {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(6) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovelCoverSkeleton(Modifier.fillMaxWidth().height(214.dp).clip(MslDesignTokens.cardShape))
                    NovelCoverSkeleton(Modifier.fillMaxWidth(0.78f).height(14.dp).clip(MslDesignTokens.pillShape))
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovelCoverSkeleton(Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NovelCoverSkeleton(Modifier.fillMaxWidth(0.78f).height(14.dp).clip(MslDesignTokens.pillShape))
                        NovelCoverSkeleton(Modifier.fillMaxWidth(0.48f).height(12.dp).clip(MslDesignTokens.pillShape))
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
            color = MslDesignTokens.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "أضف الروايات التي تريد الرجوع إليها لاحقًا.",
            color = MslDesignTokens.textSecondary,
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
            color = MslDesignTokens.textPrimary,
        )
        if (hasError) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) {
                Text("إعادة المحاولة")
            }
        } else if (query.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("جرّب عنوانًا آخر أو تحقق من المصدر المحدد.", color = MslDesignTokens.textSecondary)
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
        val context = LocalContext.current
        var showNovelComments by remember { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val readStore = remember { NovelReadStore(context) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val detailsScope = rememberCoroutineScope()
        var readKeys by remember { mutableStateOf(emptySet<String>()) }
        var downloadedPaths by remember { mutableStateOf(emptySet<String>()) }
        var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
        var downloadingPaths by remember { mutableStateOf(emptySet<String>()) }
        var bulkDownloading by remember { mutableStateOf(false) }
        var chapterOrder by remember(sourceId, path) { mutableStateOf("source") }
        var chapterReadFilter by remember(sourceId, path) { mutableStateOf("all") }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        val favoriteSnapshot by MslFavorites.snapshot.collectAsState()
        val offlineStore = remember { NovelOfflineStore(context) }
        LaunchedEffect(Unit) {
            if (!favoriteSnapshot.loaded && !favoriteSnapshot.loading) MslFavorites.load(context)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    manager.ensureLoaded()
                    manager.details(sourceId, path)
                }
            }
            val fetched = r.getOrNull()
            if (fetched?.isAdultContent() == true) {
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
            downloadedPaths = withContext(Dispatchers.IO) {
                current.chapters.orEmpty().filter { offlineStore.contains(sourceId, current.path, it.path) }.map { it.path }.toSet()
            }
        }
        DisposableEffect(lifecycleOwner, readStore) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    detailsScope.launch { readKeys = withContext(Dispatchers.IO) { readStore.all() } }
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
                        saveNovelOfflineChapter(context, manager, offlineStore, sourceId, current, fallbackName, index, chapter)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error.message ?: "تعذّر تحميل ${chapter.name}."
                    }
                    if (failure == null) {
                        downloadedPaths = downloadedPaths + chapter.path
                        Toast.makeText(context, "تم تحميل ${chapter.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    downloadingPaths = downloadingPaths - chapter.path
                }
            }
        }

        Scaffold(
            topBar = {
                NovelToolbar(
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
                            modifier = Modifier.semantics {
                                contentDescription = if (isFavorite) "إزالة $fallbackName من المفضلة" else "إضافة $fallbackName إلى المفضلة"
                            },
                            onClick = {
                                detailsScope.launch {
                                    val d = details
                                    val failure = MslFavorites.toggle(
                                        context,
                                        MslFavoriteItem(
                                            kind = MslFavoriteKind.NOVEL,
                                            itemKey = "$sourceId::$path",
                                            title = d?.name ?: fallbackName,
                                            cover = d?.cover,
                                            sourceId = sourceId,
                                        ),
                                    )
                                    if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                                }
                            },
                        ) {
                            Text(
                                if (isFavorite) "★" else "☆",
                                fontSize = 26.sp,
                                color = if (isFavorite) Color(0xFFFBBF24) else MslDesignTokens.textSecondary,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val d = details
            if (d == null) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    if (error ==
                        null
                    ) {
                        CircularProgressIndicator()
                    } else {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 30.dp)) {
                    item {
                        AsyncImage(
                            model = d.cover,
                            contentDescription = d.name,
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                        )
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                d.name ?: fallbackName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val meta = listOfNotNull(
                                d.author?.takeIf { it.isNotBlank() },
                                d.status?.takeIf { it.isNotBlank() },
                                d.genres?.takeIf { it.isNotBlank() },
                            ).joinToString(" • ")
                            if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            eu.kanade.tachiyomi.mslime.MslSocialStats(
                                eu.kanade.tachiyomi.mslime.MslSocial.titleKey("novel", sourceId, path),
                            )
                            if (!d.summary.isNullOrBlank()) {
                                Text(
                                    d.summary!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showNovelComments = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("💬 التعليقات")
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("الفصول", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (selectedPaths.isNotEmpty()) {
                                    Button(
                                        enabled = !bulkDownloading,
                                        onClick = {
                                            val selected = d.chapters.orEmpty().mapIndexedNotNull { chapterIndex, chapter ->
                                                if (chapter.path in selectedPaths) chapterIndex to chapter else null
                                            }
                                            if (selected.isEmpty()) return@Button
                                            bulkDownloading = true
                                            detailsScope.launch {
                                                var succeeded = 0
                                                var failed = 0
                                                try {
                                                    selected.forEach { (chapterIndex, chapter) ->
                                                        if (chapter.path in downloadedPaths) {
                                                            succeeded++
                                                        } else {
                                                            downloadingPaths = downloadingPaths + chapter.path
                                                            val failure = try {
                                                                saveNovelOfflineChapter(context, manager, offlineStore, sourceId, d, fallbackName, chapterIndex, chapter)
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
                                                        }
                                                    }
                                                } finally {
                                                    bulkDownloading = false
                                                    selectedPaths = emptySet()
                                                }
                                                Toast.makeText(context, "اكتمل التحميل: $succeeded ناجح، $failed تعذّر", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent),
                                    ) { Text(if (bulkDownloading) "جارٍ التحميل…" else "تحميل المحدد (${selectedPaths.size})") }
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("ترتيب الفصول", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf("source" to "المصدر", "asc" to "تصاعدي", "desc" to "تنازلي").forEach { (value, label) ->
                                    TextButton(onClick = { chapterOrder = value }, modifier = Modifier.weight(1f)) {
                                        Text(label, color = if (chapterOrder == value) MslDesignTokens.accentBright else MslDesignTokens.textSecondary)
                                    }
                                }
                            }
                            Text("حالة القراءة", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf("all" to "الكل", "unread" to "غير المقروء", "read" to "المقروء").forEach { (value, label) ->
                                    TextButton(onClick = { chapterReadFilter = value }, modifier = Modifier.weight(1f)) {
                                        Text(label, color = if (chapterReadFilter == value) MslDesignTokens.accentBright else MslDesignTokens.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                    val orderedChapters = orderNovelChapters(
                        d.chapters.orEmpty(),
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
                    itemsIndexed(
                        items = visibleChapters,
                        key = { _, entry -> entry.second.path },
                    ) { visibleIndex, entry ->
                        val (index, chapter) = entry
                        val isRead = novelReadMarkerKey(sourceId, d.path, chapter.path) in readKeys
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).combinedClickable(
                                onClick = {
                                    if (selectedPaths.isNotEmpty()) {
                                        selectedPaths = if (chapter.path in selectedPaths) selectedPaths - chapter.path else selectedPaths + chapter.path
                                    } else {
                                        navigator.push(
                                            NovelReaderScreen(
                                                sourceId = sourceId,
                                                novelPath = d.path,
                                                novelName = d.name ?: fallbackName,
                                                novelCover = d.cover,
                                                chapters = visibleChapters.map { it.second },
                                                chapterIndex = visibleIndex,
                                                sourceChapterIndices = visibleChapters.map { it.first },
                                            ),
                                        )
                                    }
                                },
                                onLongClick = {
                                    selectedPaths = if (chapter.path in selectedPaths) selectedPaths - chapter.path else selectedPaths + chapter.path
                                },
                            ),
                            shape = MslDesignTokens.compactCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (chapter.path in selectedPaths) MslDesignTokens.accent.copy(alpha = 0.24f) else if (isRead) MslDesignTokens.surfaceHighest else MslDesignTokens.surface,
                            ),
                            border = BorderStroke(1.dp, if (isRead) MslDesignTokens.accent.copy(alpha = 0.28f) else MslDesignTokens.border),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        chapter.name,
                                        color = if (isRead) MslDesignTokens.textMuted else MslDesignTokens.textPrimary,
                                        fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                                    )
                                    chapter.releaseTime?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (isRead) {
                                    Text("مقروء ✓", color = MslDesignTokens.accentBright, style = MaterialTheme.typography.labelMedium)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .semantics {
                                            contentDescription = if (chapter.path in downloadedPaths) "${chapter.name} محمّل" else "تحميل ${chapter.name}"
                                        }
                                        .clickable(
                                            enabled = chapter.path in downloadedPaths || chapter.path in downloadingPaths || MslWallet.enabled,
                                            onClick = { requestDownload(index, chapter) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(if (chapter.path in downloadedPaths) "✓" else if (chapter.path in downloadingPaths) "…" else "⇩", color = MslDesignTokens.accentBright, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
    val reservationId = UUID.randomUUID().toString()
    val reservation = try {
        withContext(Dispatchers.IO) {
            MslWallet.reserveDownload(context, reservationId, "novel:$sourceId:${details.path}:${chapter.path}")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { withContext(Dispatchers.IO) { MslWallet.refundDownload(context, reservationId) } }
        return failure.message ?: "تعذّر التحقق من رصيد التنزيل."
    }
    if (reservation == null) {
        runCatching { withContext(Dispatchers.IO) { MslWallet.refundDownload(context, reservationId) } }
        return "تعذّر التحقق من رصيد التنزيل."
    }
    if (!reservation.allowed) return context.stringResource(MR.strings.coin_download_no_balance)
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
        runCatching { withContext(Dispatchers.IO) { MslWallet.refundDownload(context, reservationId) } }
        return saved.exceptionOrNull()?.message ?: "تعذّر تحميل الفصل."
    }
    runCatching { withContext(Dispatchers.IO) { MslWallet.commitDownload(context, reservationId) } }
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
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val historyStore = remember { NovelHistoryStore(context) }
        val readStore = remember { NovelReadStore(context) }
        val offlineStore = remember { NovelOfflineStore(context) }
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val dark = isSystemInDarkTheme()
        val readerPrefs = remember {
            context.getSharedPreferences("manhwa_slime_reader_prefs", android.content.Context.MODE_PRIVATE)
        }
        var fontSize by remember {
            mutableFloatStateOf(readerPrefs.getFloat("font_size", 20f).coerceIn(14f, 32f))
        }
        var lineSpacing by remember {
            mutableFloatStateOf(readerPrefs.getFloat("line_spacing", 2.05f).coerceIn(1.2f, 3f))
        }
        var fontFamily by remember {
            mutableStateOf(readerPrefs.getString("font_family", "Amiri") ?: "Amiri")
        }
        var textAlignment by remember {
            mutableStateOf(readerPrefs.getString("text_alignment", "right") ?: "right")
        }
        var readingMargin by remember {
            mutableFloatStateOf(readerPrefs.getFloat("reading_margin", 20f).coerceIn(8f, 40f))
        }
        var showReaderSettings by remember { mutableStateOf(false) }
        var fullscreen by remember { mutableStateOf(false) }

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

        var html by remember { mutableStateOf<String?>(null) }
        var readerWebView by remember { mutableStateOf<WebView?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var offlineSaved by remember { mutableStateOf(false) }
        var offlineSaving by remember { mutableStateOf(false) }

        LaunchedEffect(currentIndex, sourceId, novelPath, offlineOnly, chapters.getOrNull(currentIndex)?.path) {
            val chapter = chapters.getOrNull(currentIndex)

            if (chapter == null) {
                error = context.stringResource(MR.strings.coin_chapter_missing)
                loading = false
                return@LaunchedEffect
            }

            loading = true
            html = null
            error = null
            val cachedContent = runCatchingCancellable {
                withContext(Dispatchers.IO) { offlineStore.read(sourceId, novelPath, chapter.path) }
            }.getOrNull()
            offlineSaved = cachedContent != null

            val result = runCatchingCancellable {
                if (offlineOnly) cachedContent ?: error("هذا الفصل غير محفوظ على الجهاز.")
                else cachedContent ?: manager.chapter(sourceId, chapter.path)
            }

            result.onSuccess { content ->
                html = content

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

            result.onFailure {
                error = it.message ?: context.stringResource(MR.strings.coin_chapter_load_failed)
            }

            loading = false
        }

        val hasPrevious = currentIndex > 0
        val hasNext = currentIndex < chapters.lastIndex
        var currentChapterMarkedRead by remember(currentIndex) { mutableStateOf(false) }
        fun markCurrentChapterRead() {
            if (currentChapterMarkedRead) return
            val chapter = chapters.getOrNull(currentIndex) ?: return
            currentChapterMarkedRead = true
            scope.launch(Dispatchers.IO) { readStore.markRead(sourceId, novelPath, chapter.path) }
        }

        Scaffold(
            topBar = {
                if (!fullscreen) {
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
                            enabled = MslWallet.enabled && html != null && !loading && !offlineSaving && !offlineSaved,
                            onClick = {
                                val chapter = chapters.getOrNull(currentIndex) ?: return@TextButton
                                val content = html ?: return@TextButton
                                scope.launch {
                                    offlineSaving = true
                                    try {
                                        val reservationId = UUID.randomUUID().toString()
                                        val reservation = runCatching {
                                            withContext(Dispatchers.IO) {
                                                MslWallet.reserveDownload(
                                                    context,
                                                    reservationId,
                                                    "novel:$sourceId:$novelPath:${chapter.path}",
                                                )
                                            }
                                        }.getOrNull()
                                        if (reservation == null) {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    MslWallet.refundDownload(context, reservationId)
                                                }
                                            }
                                            snackbarHostState.showSnackbar(
                                                context.stringResource(MR.strings.coin_download_balance_check_failed),
                                            )
                                            return@launch
                                        }
                                        if (!reservation.allowed) {
                                            snackbarHostState.showSnackbar(
                                                context.stringResource(MR.strings.coin_download_no_balance),
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
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    MslWallet.refundDownload(context, reservationId)
                                                }
                                            }
                                            snackbarHostState.showSnackbar(
                                                context.stringResource(MR.strings.coin_download_save_failed),
                                            )
                                            return@launch
                                        }
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                MslWallet.commitDownload(context, reservationId)
                                            }
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
                if (!fullscreen) {
                Surface(
                    tonalElevation = 4.dp,
                ) {
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
                        TextButton(onClick = { markCurrentChapterRead() }) {
                            Text(
                                if (currentChapterMarkedRead) "مقروء ✓" else "تمت القراءة",
                                color = if (currentChapterMarkedRead) MslDesignTokens.accentBright else MslDesignTokens.textMuted,
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

                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        Text(
                            text = error ?: "حدث خطأ",
                            color = MaterialTheme.colorScheme.error,
                        )
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
                            webViewClient = WebViewClient()
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.cacheMode =
                            WebSettings.LOAD_DEFAULT
                        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                                fullscreen = !fullscreen
                                return true
                            }
                        })
                        setOnTouchListener { _, event ->
                            detector.onTouchEvent(event)
                            false
                        }
                    }
                },
                update = { webView ->
                            val bg = if (dark) "#10131A" else "#F7F8FC"
                            val fg = if (dark) "#E7EAF2" else "#252A36"
                    val accent = if (dark) "#A99BFF" else "#6652D9"
                    val cssFont = when (fontFamily) {
                        "Amiri" -> "MslAmiri"
                        "Tajawal" -> "MslTajawal"
                        else -> "MslLalezar"
                    }
                    webView.setOnScrollChangeListener { view, _, scrollY, _, _ ->
                        if (scrollY > 0 && !view.canScrollVertically(1) && !loading) markCurrentChapterRead()
                    }

                    val style = """
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                        @font-face { font-family: 'MslAmiri'; src: url('fonts/Amiri-Regular.ttf'); }
                        @font-face { font-family: 'MslTajawal'; src: url('fonts/msl_font_regular.ttf'); }
                        @font-face { font-family: 'MslLalezar'; src: url('fonts/msl_font_display.ttf'); }
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
                                    background: ${if (dark) "#191D27" else "#ECEAFF"};
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
                            if (openingBody == null) "<html><head>$style</head><body>$sourceHtml</body></html>"
                            else sourceHtml.replaceRange(openingBody.range, openingBody.value + style)
                        }
                        sourceHtml.contains("<html", ignoreCase = true) ->
                            sourceHtml.replaceFirst(Regex("<html[^>]*>", RegexOption.IGNORE_CASE), "<html><head>$style</head>")
                        else -> "<!doctype html><html><head>$style</head><body>$sourceHtml</body></html>"
                    }
                    val renderKey = "$currentIndex|$fontSize|$lineSpacing|$fontFamily|$textAlignment|$readingMargin|$dark"
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
                AlertDialog(
                    onDismissRequest = { showReaderSettings = false },
                    title = { Text("تخصيص القراءة") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("حجم الخط: ${fontSize.toInt()}")
                            Slider(
                                value = fontSize,
                                onValueChange = {
                                    fontSize = it
                                    readerPrefs.edit().putFloat("font_size", it).apply()
                                },
                                valueRange = 14f..32f,
                                steps = 17,
                            )
                            Text("تباعد الأسطر: ${String.format(java.util.Locale.US, "%.2f", lineSpacing)}")
                            Slider(
                                value = lineSpacing,
                                onValueChange = {
                                    lineSpacing = it
                                    readerPrefs.edit().putFloat("line_spacing", it).apply()
                                },
                                valueRange = 1.2f..3f,
                                steps = 17,
                            )
                            Text("نوع الخط")
                            listOf("Amiri", "Tajawal", "Lalezar").forEach { family ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            fontFamily = family
                                            readerPrefs.edit().putString("font_family", family).apply()
                                        }
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = fontFamily == family,
                                        onClick = {
                                            fontFamily = family
                                            readerPrefs.edit().putString("font_family", family).apply()
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(family)
                                }
                            }
                            Text("محاذاة النص")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("right" to "يمين", "center" to "وسط", "left" to "يسار").forEach { (value, label) ->
                                    FilterChip(
                                        selected = textAlignment == value,
                                        onClick = {
                                            textAlignment = value
                                            readerPrefs.edit().putString("text_alignment", value).apply()
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            Text("هامش القراءة: ${readingMargin.toInt()} px")
                            Slider(
                                value = readingMargin,
                                onValueChange = {
                                    readingMargin = it
                                    readerPrefs.edit().putFloat("reading_margin", it).apply()
                                },
                                valueRange = 8f..40f,
                                steps = 7,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showReaderSettings = false }) { Text("تم") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                fontSize = 20f
                                lineSpacing = 2.05f
                                fontFamily = "Amiri"
                                textAlignment = "right"
                                readingMargin = 20f
                                readerPrefs.edit()
                                    .putFloat("font_size", fontSize)
                                    .putFloat("line_spacing", lineSpacing)
                                    .putString("font_family", fontFamily)
                                    .putString("text_alignment", textAlignment)
                                    .putFloat("reading_margin", readingMargin)
                                    .apply()
                            },
                        ) { Text("إعادة الضبط") }
                    },
                )
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
