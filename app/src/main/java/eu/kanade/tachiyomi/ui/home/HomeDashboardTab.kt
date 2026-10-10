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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.category.LibraryCategoryBrowserScreen
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = R.drawable.ic_mihon,
                contentDescription = null,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)),
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MANHWA",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    text = "SLIME",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MslDesignTokens.accentBright,
                    letterSpacing = 0.8.sp,
                )
            }
            IconButton(onClick = { navigator.push(GlobalSearchScreen(searchQuery)) }) {
                Icon(MaterialSymbols.Rounded.Search, contentDescription = "بحث")
            }
            IconButton(onClick = { tabNavigator.current = UpdatesTab }) {
                Icon(MaterialSymbols.Rounded.NewReleases, contentDescription = "آخر التحديثات")
            }
        }

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
            onAction = { navigator.push(LibraryCategoryBrowserScreen()) },
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
