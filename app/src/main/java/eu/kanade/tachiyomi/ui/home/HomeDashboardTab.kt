package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.MslReadingTasks
import eu.kanade.tachiyomi.mslime.MslSupabase
import eu.kanade.tachiyomi.mslime.MslWallet
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.NewReleases
import mihon.icons.materialsymbols.rounded.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.model.Category

/** Home dashboard built only from items already present in the user's library and reading history. */
data object HomeDashboardTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = "الرئيسية",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.LocalLibrary),
        )

    @Composable
    override fun Content() {
        HomeDashboardContent()
    }
}

@Composable
private fun HomeDashboardContent() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val tabNavigator = LocalTabNavigator.current
    val libraryViewModel = metroViewModel<LibraryViewModel>()
    val updatesViewModel = metroViewModel<UpdatesViewModel>()
    val historyViewModel = metroViewModel<HistoryViewModel>()
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val updatesState by updatesViewModel.state.collectAsStateWithLifecycle()
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }

    val recentHistory = remember(historyState.list) {
        historyState.list.orEmpty()
            .mapNotNull { (it as? HistoryUiModel.Item)?.item }
            .take(8)
    }
    val latestHistory = recentHistory.firstOrNull()
    val firstLibraryItem = libraryState.libraryData.favorites.firstOrNull()
    val updates = remember(updatesState.items) {
        updatesState.items.distinctBy { it.update.mangaId }.take(10)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            placeholder = { Text("ابحث عن مانهوا أو رواية...") },
            leadingIcon = { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { navigator.push(GlobalSearchScreen(searchQuery)) }) {
                    Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = "تنفيذ البحث")
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        eu.kanade.tachiyomi.mslime.MslDailyRewardCard()
        MslReadingTasksCard()

        val heroTitle = latestHistory?.title
            ?: firstLibraryItem?.libraryManga?.manga?.title
        val heroCover = latestHistory?.coverData
            ?: firstLibraryItem?.libraryManga?.manga?.thumbnailUrl
        val heroSubtitle = latestHistory?.let {
            if (it.chapterNumber >= 0) "تابع من الفصل ${it.chapterNumber}" else "تابع قراءتك الأخيرة"
        } ?: if (firstLibraryItem != null) "من مكتبتك" else "اكتشف عناوين جديدة من مصادرك"
        val heroAction: () -> Unit = {
            when {
                latestHistory != null -> {
                    context.startActivity(
                        ReaderActivity.newIntent(context, latestHistory.mangaId, latestHistory.chapterId),
                    )
                }
                firstLibraryItem != null -> navigator.push(MangaScreen(firstLibraryItem.libraryManga.manga.id))
                else -> tabNavigator.current = BrowseTab
            }
        }

        HomeHeroCard(
            title = heroTitle ?: "مرحبًا بك في ManhwaSlime",
            subtitle = heroSubtitle,
            cover = heroCover,
            actionLabel = if (latestHistory != null) "تابع القراءة" else "ابدأ الاستكشاف",
            onClick = heroAction,
        )

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DashboardShortcut(
                title = "المكتبة",
                icon = MaterialSymbols.Rounded.CollectionsBookmark,
                modifier = Modifier.weight(1f),
                onClick = { tabNavigator.current = LibraryTab },
            )
            DashboardShortcut(
                title = "اكتشف",
                icon = MaterialSymbols.Rounded.Explore,
                modifier = Modifier.weight(1f),
                onClick = { tabNavigator.current = BrowseTab },
            )
            DashboardShortcut(
                title = "الروايات",
                icon = MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
                modifier = Modifier.weight(1f),
                onClick = { tabNavigator.current = NovelTab },
            )
            DashboardShortcut(
                title = "التحديثات",
                icon = MaterialSymbols.Rounded.NewReleases,
                modifier = Modifier.weight(1f),
                onClick = { tabNavigator.current = UpdatesTab },
            )
        }

        Spacer(Modifier.height(22.dp))
        DashboardSectionHeader(
            title = "أحدث التحديثات",
            actionLabel = "عرض الكل",
            onAction = { tabNavigator.current = UpdatesTab },
        )
        if (updates.isEmpty()) {
            EmptyDashboardCard(
                message = if (updatesState.isLoading) {
                    "جارٍ تحميل تحديثات مكتبتك..."
                } else {
                    "لا توجد تحديثات جديدة في مكتبتك حاليًا."
                },
                actionLabel = if (updatesState.isLoading) null else "فتح التحديثات",
                onAction = { tabNavigator.current = UpdatesTab },
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(updates, key = { it.update.chapterId }) { update ->
                    val libraryItem = libraryState.libraryData.favoritesById[update.update.mangaId]
                    val manga = libraryItem?.libraryManga?.manga
                    DashboardTitleCard(
                        title = update.update.mangaTitle,
                        subtitle = update.update.chapterName,
                        cover = manga?.thumbnailUrl,
                        onClick = { navigator.push(MangaScreen(update.update.mangaId)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        DashboardSectionHeader(
            title = "تصنيفات مكتبتك",
            actionLabel = "عرض الكل",
            onAction = { navigator.push(CategoryScreen()) },
        )
        val categories = libraryState.displayedCategories
        if (categories.isEmpty()) {
            EmptyDashboardCard(
                message = "ستظهر هنا مجموعاتك بعد إضافة عناوين إلى المكتبة.",
                actionLabel = "إدارة التصنيفات",
                onAction = { navigator.push(CategoryScreen()) },
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    val categoryItems = libraryState.getItemsForCategory(category)
                    val cover = categoryItems.firstOrNull()?.libraryManga?.manga?.thumbnailUrl
                    CategoryTile(
                        category = category,
                        count = categoryItems.size,
                        cover = cover,
                        onClick = {
                            val index = categories.indexOf(category)
                            if (index >= 0) libraryViewModel.updateActiveCategoryIndex(index)
                            tabNavigator.current = LibraryTab
                        },
                    )
                }
            }
        }

        if (recentHistory.size > 1) {
            Spacer(Modifier.height(22.dp))
            DashboardSectionHeader(
                title = "تابع القراءة",
                actionLabel = "سجل القراءة",
                onAction = { tabNavigator.current = HistoryTab },
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(recentHistory.drop(1), key = { it.chapterId }) { history ->
                    DashboardTitleCard(
                        title = history.title,
                        subtitle = if (history.chapterNumber >= 0) "الفصل ${history.chapterNumber}" else "آخر قراءة",
                        cover = history.coverData,
                        onClick = {
                            context.startActivity(
                                ReaderActivity.newIntent(context, history.mangaId, history.chapterId),
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun MslReadingTasksCard() {
    if (!MslWallet.enabled) return
    val context = LocalContext.current
    if (MslSupabase.uid(context).isBlank()) return
    val scope = rememberCoroutineScope()
    val refreshVersion by MslReadingTasks.refreshVersion.collectAsStateWithLifecycle()
    var snapshot by remember { mutableStateOf<MslReadingTasks.Snapshot?>(null) }
    var claimingTaskId by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var claimFailed by remember { mutableStateOf(false) }

    LaunchedEffect(context, refreshVersion) {
        val loaded = withContext(Dispatchers.IO) { MslReadingTasks.load(context) }
        snapshot = loaded
        loadFailed = loaded == null
    }

    val current = snapshot
    if (current == null) {
        if (!loadFailed) return
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MslDesignTokens.cardShape,
            colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("تعذر تحميل مهام اليوم؛ تحقق من اتصال الإنترنت.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = MslReadingTasks::invalidate) { Text("إعادة المحاولة") }
            }
        }
        return
    }
    if (current.tasks.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("مهام القراءة اليومية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (claimFailed) {
                Text("تعذر استلام المكافأة؛ تحقق من الاتصال ثم أعد المحاولة.", color = MslDesignTokens.warning, style = MaterialTheme.typography.bodySmall)
            }
            current.tasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            task.description.ifBlank { "أكمل القراءة لتحصل على عملات." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MslDesignTokens.textSecondary,
                        )
                        Text(
                            "${task.progress}/${task.targetCount} فصول · ${task.rewardCoins} عملة",
                            style = MaterialTheme.typography.labelMedium,
                            color = MslDesignTokens.accentBright,
                        )
                    }
                    when {
                        task.claimed -> Text("تم الاستلام", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelMedium)
                        task.claimable -> TextButton(
                            enabled = claimingTaskId == null,
                            onClick = {
                                claimingTaskId = task.id
                                claimFailed = false
                                // The RPC chooses the configured amount and is idempotent per user/day.
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { MslReadingTasks.claim(context, task.id) }
                                    if (result != null) {
                                        if (result.claimed || result.alreadyClaimed) {
                                            snapshot = snapshot?.let { value ->
                                                value.copy(tasks = value.tasks.map { item ->
                                                    if (item.id == task.id) item.copy(claimed = true, claimable = false) else item
                                                })
                                            }
                                        }
                                        withContext(Dispatchers.IO) { MslWallet.refresh(context) }
                                    } else {
                                        claimFailed = true
                                    }
                                    claimingTaskId = null
                                }
                            },
                        ) { Text("استلام") }
                        else -> Text("قيد التقدم", color = MslDesignTokens.textMuted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroCard(
    title: String,
    subtitle: String,
    cover: Any?,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(220.dp)
            .clip(MslDesignTokens.cardShape)
            .background(MslDesignTokens.surfaceRaised)
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(MslDesignTokens.heroGradient))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (cover == null) "اكتشف عالمك التالي" else "اختيارك الحالي",
                style = MaterialTheme.typography.labelLarge,
                color = MslDesignTokens.textSecondary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MslDesignTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MslDesignTokens.textSecondary,
            )
            Surface(
                shape = MslDesignTokens.pillShape,
                color = MslDesignTokens.accent,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    color = MslDesignTokens.textPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DashboardShortcut(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(82.dp).clickable(onClick = onClick),
        shape = MslDesignTokens.compactCardShape,
        color = MslDesignTokens.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MslDesignTokens.accentBright, modifier = Modifier.size(23.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun DashboardSectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onAction) {
            Text(actionLabel, color = MslDesignTokens.accentBright)
        }
    }
}

@Composable
private fun DashboardTitleCard(
    title: String,
    subtitle: String,
    cover: Any?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(122.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MangaCover.Book(
            data = cover,
            modifier = Modifier.width(122.dp),
            contentDescription = title,
            shape = MslDesignTokens.compactCardShape,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryTile(
    category: Category,
    count: Int,
    cover: Any?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(148.dp).height(132.dp).clickable(onClick = onClick),
        shape = MslDesignTokens.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, MslDesignTokens.background.copy(alpha = 0.96f))),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    category.name.ifBlank {
                        "المكتبة"
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$count عنوان", style = MaterialTheme.typography.labelSmall, color = MslDesignTokens.textSecondary)
            }
        }
    }
}

@Composable
private fun EmptyDashboardCard(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MslDesignTokens.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MslDesignTokens.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = MslDesignTokens.accentBright) }
            }
        }
    }
}
