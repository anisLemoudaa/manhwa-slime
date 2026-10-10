@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import eu.kanade.tachiyomi.mslime.MslCloudSync
import eu.kanade.tachiyomi.mslime.MslCommentsDialog
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.MslWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

private data class NovelUiState(
    val sources: List<NovelSourceRuntime> = emptyList(),
    val selectedSource: String? = null,
    val novels: List<NovelItem> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val repoDialog: Boolean = false,
    val repoUrl: String = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
    val repoEntries: List<NovelRepositoryEntry> = emptyList(),
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
    val favoritesStore = remember { NovelFavoritesStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NovelUiState()) }
    var favoritesRevision by remember { mutableIntStateOf(0) }
    var pluginsInitialized by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val favoriteEntries = remember(favoritesRevision) {
        favoritesStore.all()
    }
    val favoriteKeys = remember(favoriteEntries) {
        favoriteEntries.map { "${it.sourceId}::${it.novelPath}" }.toSet()
    }

    fun reload(sourceId: String? = state.selectedSource) {
        searchJob?.cancel()
        scope.launch {
            // Load installed JS plugins once instead of reinitializing every source change.
            val sources = runCatching {
                if (!pluginsInitialized) {
                    manager.ensureLoaded()
                    pluginsInitialized = true
                }
                manager.installed()
            }.getOrDefault(emptyList())

            val sid = sourceId ?: sources.firstOrNull()?.id
            state = state.copy(
                sources = sources,
                selectedSource = sid,
                loading = true,
                error = null,
                info = null,
            )

            if (sid != null) {
                val data = runCatching { manager.latest(sid) }
                // Ignore a late response if the user has already selected another source.
                if (state.selectedSource == sid) {
                    state = state.copy(
                        novels = data.getOrDefault(emptyList()),
                        loading = false,
                        error = data.exceptionOrNull()?.message,
                    )
                }
            } else {
                state = state.copy(novels = emptyList(), loading = false)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    NovelShell(
        state = state,
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
                MslCloudSync.syncNovelFavoritesNow(context)
            }
        },
        onToggleFavoriteFilter = {
            state = state.copy(showFavorites = !state.showFavorites)
        },
        onSearch = { q ->
            state = state.copy(query = q, info = null)
            searchJob?.cancel()

            val sid = state.selectedSource
            val query = q.trim()

            if (sid != null && query.isNotEmpty()) {
                // Wait until typing pauses before making a network/plugin request.
                searchJob = scope.launch {
                    delay(350)
                    val r = runCatching { manager.search(sid, query) }

                    // Do not let stale results overwrite a newer query or selected source.
                    if (state.selectedSource == sid && state.query.trim() == query) {
                        state = state.copy(
                            novels = r.getOrDefault(emptyList()),
                            error = r.exceptionOrNull()?.message,
                        )
                    }
                }
            } else if (query.isEmpty()) {
                reload()
            }
        },
        onSelectSource = { sid ->
            searchJob?.cancel()
            reload(sid)
        },
        onAddRepository = { state = state.copy(repoDialog = true, error = null, info = null) },
        onInputModeChange = {
            state = state.copy(inputMode = it, repoEntries = emptyList(), error = null, info = null)
        },
        onRepoUrlChange = { state = state.copy(repoUrl = it, error = null, info = null) },
        onInstall = { entry ->
            scope.launch {
                val r = runCatching { manager.install(entry, state.repoUrl.trim()) }
                if (r.isFailure) {
                    state = state.copy(error = r.exceptionOrNull()?.message)
                } else {
                    state = state.copy(repoDialog = false, info = "تم تثبيت ${r.getOrThrow().name}")
                    reload(r.getOrThrow().id)
                }
            }
        },
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
            val sid = state.selectedSource
            if (sid != null) navigator.push(NovelDetailsScreen(sid, item.path, item.name))
        },
        onCloseRepo = { state = state.copy(repoDialog = false, error = null) },
        onLoadRepo = {
            scope.launch {
                val result = runCatching { manager.loadRepository(state.repoUrl) }
                state = state.copy(
                    repoEntries = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message,
                    info = result.getOrNull()?.let { "تم العثور على ${it.size} مصدرًا" },
                )
            }
        },
    )
}

@Composable
private fun NovelShell(
    state: NovelUiState,
    favoriteKeys: Set<String>,
    onToggleFavorite: (NovelItem) -> Unit,
    onToggleFavoriteFilter: () -> Unit,
    onSearch: (String) -> Unit,
    onSelectSource: (String) -> Unit,
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
                        .background(MslDesignTokens.backgroundRaised)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "الروايات",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MslDesignTokens.textPrimary,
                            )
                            Text(
                                "(قيد التطوير)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MslDesignTokens.textMuted,
                            )
                        }
                        Text(
                            "اكتشف قراءتك القادمة",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MslDesignTokens.textSecondary,
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onAddRepository,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MslDesignTokens.surfaceHighest,
                            contentColor = MslDesignTokens.accentBright,
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
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        AssistChip(
                            onClick = onAddRepository,
                            label = { Text("إضافة مصدر") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MslDesignTokens.accent,
                                labelColor = MslDesignTokens.textPrimary,
                            ),
                            border = null,
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
                            selected = state.selectedSource == source.id,
                            onClick = { onSelectSource(source.id) },
                            label = {
                                Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MslDesignTokens.surfaceRaised,
                                labelColor = MslDesignTokens.textSecondary,
                                selectedContainerColor = MslDesignTokens.accent.copy(alpha = 0.28f),
                                selectedLabelColor = MslDesignTokens.textPrimary,
                            ),
                        )
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
                        Text(it, color = MslDesignTokens.danger, style = MaterialTheme.typography.bodySmall)
                    }
                }

                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = MslDesignTokens.accentBright)
                    }
                    state.sources.isEmpty() -> EmptyNovelSources(onAddRepository)
                    else -> {
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
                        } else if (visibleNovels.isEmpty()) {
                            EmptyNovelResults(state.query, state.error != null)
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                                if (state.repoEntries.isNotEmpty()) {
                                    HorizontalDivider(color = MslDesignTokens.border)
                                    Text(
                                        "المصادر المتاحة: ${state.repoEntries.size}",
                                        fontWeight = FontWeight.SemiBold,
                                        color = MslDesignTokens.textPrimary,
                                    )
                                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                        items(state.repoEntries) { entry ->
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
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(item) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(206.dp)
                        .clip(MslDesignTokens.compactCardShape),
                ) {
                    AsyncImage(
                        model = item.cover,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MslDesignTokens.heroGradient),
                    )
                }

                Text(
                    item.name,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = MslDesignTokens.textPrimary,
                )
            }

            FilledTonalIconButton(
                onClick = { onToggleFavorite(item) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MslDesignTokens.background.copy(alpha = 0.82f),
                    contentColor = MslDesignTokens.warning,
                ),
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
private fun EmptyNovelResults(query: String, hasError: Boolean) {
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
        if (query.isNotBlank() && !hasError) {
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
        val favoritesStore = remember { NovelFavoritesStore(context) }
        var favoriteRevision by remember { mutableIntStateOf(0) }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val r = runCatching { manager.details(sourceId, path) }
            details = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
        val isFavorite = remember(favoriteRevision, details) {
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
                                MslCloudSync.syncNovelFavoritesNow(context)
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
                            Text("الفصول", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    itemsIndexed(d.chapters.orEmpty()) { index, chapter ->
                        ListItem(
                            modifier = Modifier.clickable {
                                val reader = NovelReaderScreen(
                                    sourceId = sourceId,
                                    novelPath = d.path,
                                    novelName = d.name ?: fallbackName,
                                    novelCover = d.cover,
                                    chapters = d.chapters.orEmpty(),
                                    chapterIndex = index,
                                )
                                navigator.push(reader)
                            },
                            headlineContent = { Text(chapter.name) },
                            supportingContent = {
                                if (!chapter.releaseTime.isNullOrBlank()) Text(chapter.releaseTime!!)
                            },
                        )
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

class NovelReaderScreen(
    private val sourceId: String,
    private val novelPath: String,
    private val novelName: String,
    private val novelCover: String?,
    private val chapters: List<NovelChapter>,
    private val chapterIndex: Int,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val historyStore = remember { NovelHistoryStore(context) }
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
            mutableStateOf(readerPrefs.getString("font_family", "Noto Naskh Arabic") ?: "Noto Naskh Arabic")
        }
        var showReaderSettings by remember { mutableStateOf(false) }

        var currentIndex by remember {
            mutableIntStateOf(
                chapterIndex.coerceIn(
                    0,
                    (chapters.size - 1).coerceAtLeast(0),
                ),
            )
        }

        var html by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var offlineSaved by remember { mutableStateOf(false) }
        var offlineSaving by remember { mutableStateOf(false) }

        LaunchedEffect(currentIndex, sourceId) {
            val chapter = chapters.getOrNull(currentIndex)

            if (chapter == null) {
                error = context.stringResource(MR.strings.coin_chapter_missing)
                loading = false
                return@LaunchedEffect
            }

            loading = true
            html = null
            error = null
            val cachedContent = runCatching {
                withContext(Dispatchers.IO) { offlineStore.read(sourceId, novelPath, chapter.path) }
            }.getOrNull()
            offlineSaved = cachedContent != null

            val result = runCatching {
                cachedContent ?: manager.chapter(sourceId, chapter.path)
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
                        chapterIndex = currentIndex,
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

        Scaffold(
            topBar = {
                TopAppBar(
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
                                                offlineStore.save(sourceId, novelPath, chapter.path, content)
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
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
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

                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = hasNext && !loading && html != null,
                            onClick = {
                                if (hasNext) {
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
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.cacheMode =
                                    WebSettings.LOAD_DEFAULT
                            }
                        },
                        update = { webView ->
                            val bg = if (dark) "#10131A" else "#F7F8FC"
                            val fg = if (dark) "#E7EAF2" else "#252A36"
                            val accent = if (dark) "#A99BFF" else "#6652D9"

                            val style = """
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <style>
                                * { box-sizing: border-box; }
                                html { background: $bg; }
                                body {
                                    font-family: "$fontFamily", "Noto Sans Arabic", sans-serif;
                                    font-size: ${fontSize}px;
                                    line-height: $lineSpacing;
                                    padding: 26px 21px 40px;
                                    margin: 0;
                                    background: $bg;
                                    color: $fg;
                                    overflow-wrap: anywhere;
                                    text-align: start;
                                }
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

                            webView.loadDataWithBaseURL(
                                null,
                                style + html.orEmpty(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
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
                            listOf("Noto Naskh Arabic", "Noto Sans Arabic", "serif", "sans-serif").forEach { family ->
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
                                fontFamily = "Noto Naskh Arabic"
                                readerPrefs.edit()
                                    .putFloat("font_size", fontSize)
                                    .putFloat("line_spacing", lineSpacing)
                                    .putString("font_family", fontFamily)
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
