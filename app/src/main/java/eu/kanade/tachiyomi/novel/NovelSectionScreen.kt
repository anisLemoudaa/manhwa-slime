@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NovelUiState()) }

    fun reload(sourceId: String? = state.selectedSource) {
        scope.launch {
            val sources = runCatching {
                manager.ensureLoaded()
                manager.installed()
            }.getOrDefault(emptyList())
            val sid = sourceId ?: sources.firstOrNull()?.id
            state = state.copy(sources = sources, selectedSource = sid, loading = true, error = null, info = null)
            if (sid != null) {
                val data = runCatching { manager.latest(sid) }
                state =
                    state.copy(
                        novels = data.getOrDefault(emptyList()),
                        loading = false,
                        error = data.exceptionOrNull()?.message,
                    )
            } else {
                state = state.copy(novels = emptyList(), loading = false)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    NovelShell(
        state = state,
        onSearch = { q ->
            state = state.copy(query = q, info = null)
            val sid = state.selectedSource
            if (sid != null && q.trim().isNotEmpty()) {
                scope.launch {
                    val r = runCatching { manager.search(sid, q.trim()) }
                    state = state.copy(novels = r.getOrDefault(emptyList()), error = r.exceptionOrNull()?.message)
                }
            } else if (q.trim().isEmpty()) {
                reload()
            }
        },
        onSelectSource = { sid -> reload(sid) },
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("الروايات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "مصادر LNReader + روابط JS مباشرة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onAddRepository) { Text("⚙") }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssistChip(onClick = onAddRepository, label = {
                        Text("إضافة مصدر")
                    })
                }
                items(state.sources) { source ->
                    FilterChip(
                        selected = state.selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text("ابحث عن رواية...") },
            )
            Spacer(Modifier.height(12.dp))

            state.info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.sources.isEmpty() -> EmptyNovelSources(onAddRepository)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.novels) { item -> NovelCard(item, onOpen) }
                }
            }
        }

        if (state.repoDialog) {
            AlertDialog(
                onDismissRequest = onCloseRepo,
                title = { Text("إضافة مصادر الروايات") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "اختر مستودع LNReader أو ألصق رابط plugin.js مباشر.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = state.inputMode == NovelInputMode.REPOSITORY,
                                onClick = { onInputModeChange(NovelInputMode.REPOSITORY) },
                                label = { Text("مستودع") },
                            )
                            FilterChip(
                                selected = state.inputMode == NovelInputMode.DIRECT_URL,
                                onClick = { onInputModeChange(NovelInputMode.DIRECT_URL) },
                                label = { Text("رابط مباشر") },
                            )
                        }
                        OutlinedTextField(
                            value = state.repoUrl,
                            onValueChange = onRepoUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("URL") },
                        )
                        if (state.inputMode == NovelInputMode.REPOSITORY) {
                            Button(onClick = onLoadRepo, modifier = Modifier.fillMaxWidth()) { Text("تحميل المستودع") }
                            if (state.repoEntries.isNotEmpty()) {
                                HorizontalDivider()
                                Text("المصادر المتاحة: ${state.repoEntries.size}", fontWeight = FontWeight.SemiBold)
                                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                    items(state.repoEntries) { entry ->
                                        ListItem(
                                            headlineContent = { Text(entry.name) },
                                            supportingContent = {
                                                Text(listOfNotNull(entry.lang, entry.version).joinToString(" • "))
                                            },
                                            trailingContent = {
                                                Button(onClick = { onInstall(entry) }) { Text("تثبيت") }
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            Button(onClick = onInstallDirect, modifier = Modifier.fillMaxWidth()) {
                                Text("تنزيل وتثبيت المصدر")
                            }
                        }
                        Text(
                            "تنبيه: المصدر يشغّل JavaScript تابعًا لجهة خارجية داخل محرك المصادر. ثبّت الروابط التي تثق بها فقط.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onCloseRepo) { Text("إغلاق") } },
            )
        }
    }
}

@Composable
private fun EmptyNovelSources(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("لا توجد مصادر روايات مثبتة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("أضف مستودعًا ثم ثبّت المصادر التي تريدها.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("إضافة مستودع") }
    }
}

@Composable
private fun NovelCard(item: NovelItem, onOpen: (NovelItem) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(item) },
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            AsyncImage(
                model = item.cover,
                contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().height(
                    190.dp,
                ).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            )
            Text(
                item.name,
                modifier = Modifier.padding(10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
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
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val r = runCatching { manager.details(sourceId, path) }
            details = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(details?.name ?: fallbackName)
                }, navigationIcon = { IconButton(onClick = { navigator.pop() }) { Text("‹", fontSize = 32.sp) } })
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
                            if (!d.summary.isNullOrBlank()) {
                                Text(
                                    d.summary!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("الفصول", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(d.chapters.orEmpty()) { chapter ->
                        ListItem(
                            modifier = Modifier.clickable {
                                navigator.push(NovelReaderScreen(sourceId, chapter.path, chapter.name))
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
    }
}

class NovelReaderScreen(
    private val sourceId: String,
    private val chapterPath: String,
    private val chapterName: String,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val isDark = isSystemInDarkTheme()
        var html by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val r = runCatching { manager.chapter(sourceId, chapterPath) }
            html = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, navigationIcon = { IconButton(onClick = { navigator.pop() }) { Text("‹", fontSize = 32.sp) } })
            },
        ) { padding ->
            when {
                html == null && error == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    factory = {
                        WebView(it).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                        }
                    },
                    update = { webView ->
                        val bg = if (isDark) "#111111" else "#ffffff"
                        val fg = if (isDark) "#eeeeee" else "#171717"
                        val style = "<style>body{font-family:sans-serif;font-size:18px;line-height:1.9;padding:18px;margin:0;background:$bg;color:$fg}img{max-width:100%;height:auto}a{color:inherit}</style>"
                        webView.loadDataWithBaseURL(null, style + html.orEmpty(), "text/html", "UTF-8", null)
                    },
                )
            }
        }
    }
}
