package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavorites
import eu.kanade.tachiyomi.ui.home.DownloadedNovelsTab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.NovelTab
import eu.kanade.tachiyomi.ui.profile.ProfileTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Person
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import mihon.app.di.appGraph
import mihon.domain.extension.model.ContentWarning

object HomeScreen : Screen() {
    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    internal val downloadedRefreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private const val TabFadeDuration = 200
    private const val TabNavigatorKey = "HomeTabs"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        TabNavigator(tab = HomeDashboardTab, key = TabNavigatorKey) { tabNavigator ->
            CompositionLocalProvider(LocalNavigator provides navigator) {
                val favorites by MslFavorites.snapshot.collectAsState()
                LaunchedEffect(Unit) {
                    MslFavorites.load(context)
                    context.appGraph.sourcePreferences.enabledContentWarnings.set(setOf(ContentWarning.SAFE))
                    context.appGraph.sourcePreferences.applyContentWarningsToInstalled.set(true)
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    scrimColor = Color.Black.copy(alpha = 0.66f),
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.widthIn(max = 292.dp),
                            drawerContainerColor = Color(0xFF0B1124),
                            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                DrawerContent(
                                    current = tabNavigator.current,
                                    favoriteCounts = MslFavoriteKind.entries.associateWith { kind ->
                                        favorites.items.count { it.kind == kind }
                                    },
                                    close = { scope.launch { drawerState.close() } },
                                    navigate = { destination ->
                                        tabNavigator.current = destination
                                        if (destination == DownloadedNovelsTab) downloadedRefreshEvents.tryEmit(Unit)
                                        scope.launch { drawerState.close() }
                                    },
                                )
                            }
                        }
                    },
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Scaffold(
                            contentWindowInsets = WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                            containerColor = MslDesignTokens.background,
                            topBar = {
                                RootTopBar(onOpenMenu = { scope.launch { drawerState.open() } })
                            },
                        ) { contentPadding ->
                            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    HomeTabContent()
                                }
                            }
                        }
                    }
                }
                }

                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
                BackHandler(enabled = !drawerState.isOpen && tabNavigator.current != HomeDashboardTab) {
                    tabNavigator.current = HomeDashboardTab
                }

                val goToLibraryTab = { tabNavigator.current = LibraryTab }
                LaunchedEffect(Unit) {
                    launch {
                        librarySearchEvent.receiveAsFlow().collect { query ->
                            goToLibraryTab()
                            LibraryTab.search(query)
                        }
                    }
                    launch {
                        openTabEvent.receiveAsFlow().collect { target ->
                            tabNavigator.current = when (target) {
                                is Tab.Library -> LibraryTab
                                Tab.Updates -> UpdatesTab
                                Tab.History -> HistoryTab
                                is Tab.Browse -> {
                                    if (target.toExtensions) BrowseTab.showExtension()
                                    BrowseTab
                                }
                                is Tab.More -> MoreTab
                            }
                            if (target is Tab.Library && target.mangaIdToOpen != null) {
                                navigator.push(MangaScreen(target.mangaIdToOpen))
                            }
                            if (target is Tab.More && target.toDownloads) navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RootTopBar(onOpenMenu: () -> Unit) {
        Surface(color = MslDesignTokens.background, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpenMenu),
                    shape = RoundedCornerShape(22.dp),
                    color = MslDesignTokens.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MslDesignTokens.border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_msl_menu), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("المزيد", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text("SLIME", color = Color(0xFF9B6BFF), fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 0.5.sp)
                        Text("MANHWA", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 0.5.sp)
                    }
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_msl_fg),
                        contentDescription = "شعار التطبيق الأصلي",
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)),
                    )
                }
            }
        }
    }

    @Composable
    private fun DrawerContent(
        current: cafe.adriel.voyager.navigator.tab.Tab,
        favoriteCounts: Map<MslFavoriteKind, Int>,
        close: () -> Unit,
        navigate: (cafe.adriel.voyager.navigator.tab.Tab) -> Unit,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("المزيد", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                IconButton(onClick = close, modifier = Modifier.size(48.dp).semantics { contentDescription = "إغلاق القائمة" }) {
                    Text("×", color = MslDesignTokens.textPrimary, fontSize = 28.sp)
                }
            }

            DrawerItem("الشاشة الرئيسية", MaterialSymbols.Rounded.LocalLibrary, current == HomeDashboardTab) { navigate(HomeDashboardTab) }
            DrawerItem("المكتبة", MaterialSymbols.Rounded.CollectionsBookmark, current == LibraryTab) { navigate(LibraryTab) }
            DrawerItem("تصفح", MaterialSymbols.Rounded.Explore, current == BrowseTab) { navigate(BrowseTab) }
            DrawerItem("الروايات", MaterialSymbols.AutoMirroredRounded.ChromeReaderMode, current == NovelTab) { navigate(NovelTab) }
            DrawerItem("الروايات المحملة", MaterialSymbols.Rounded.Download, current == DownloadedNovelsTab) { navigate(DownloadedNovelsTab) }
            DrawerItem("روايات المجتمع", MaterialSymbols.Rounded.CollectionsBookmark, current == CommunityNovelTab) { navigate(CommunityNovelTab) }

            HorizontalDivider(color = MslDesignTokens.border, modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp))
            Text("المفضلة", modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 6.dp), color = MslDesignTokens.textMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            DrawerItem(
                MslFavoriteKind.MANGA.label,
                MaterialSymbols.Rounded.CollectionsBookmark,
                current == FavoriteMangaTab,
                favoriteCounts[MslFavoriteKind.MANGA] ?: 0,
                showCount = true,
            ) { navigate(FavoriteMangaTab) }
            DrawerItem(
                MslFavoriteKind.NOVEL.label,
                MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
                current == FavoriteNovelTab,
                favoriteCounts[MslFavoriteKind.NOVEL] ?: 0,
                showCount = true,
            ) { navigate(FavoriteNovelTab) }
            DrawerItem(
                MslFavoriteKind.COMMUNITY.label,
                MaterialSymbols.Rounded.CollectionsBookmark,
                current == FavoriteCommunityTab,
                favoriteCounts[MslFavoriteKind.COMMUNITY] ?: 0,
                showCount = true,
            ) { navigate(FavoriteCommunityTab) }

            HorizontalDivider(color = MslDesignTokens.border, modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp))
            DrawerItem("الملف الشخصي", MaterialSymbols.Rounded.Person, current == ProfileTab) { navigate(ProfileTab) }
        }
    }

    @Composable
    private fun DrawerItem(
        title: String,
        icon: ImageVector,
        selected: Boolean,
        count: Int = 0,
        showCount: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) MslDesignTokens.accent else Color.Transparent)
                .clickable(onClick = onClick)
                .semantics {
                    this.selected = selected
                    role = Role.Button
                    contentDescription = if (showCount || count > 0) "$title، $count" else title
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = if (selected) Color.White else MslDesignTokens.accentBright)
            Text(title, modifier = Modifier.weight(1f), color = if (selected) Color.White else MslDesignTokens.textSecondary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            if (showCount || count > 0) Badge(containerColor = MslDesignTokens.surfaceHighest, contentColor = MslDesignTokens.accentBright) { Text(count.toString()) }
        }
    }

    @Composable
    private fun HomeTabContent() {
        val tabNavigator = LocalTabNavigator.current
        AnimatedContent(
            targetState = tabNavigator.current,
            transitionSpec = {
                materialFadeThroughIn(initialScale = 1f, durationMillis = TabFadeDuration) togetherWith
                    materialFadeThroughOut(durationMillis = TabFadeDuration)
            },
            label = "tabContent",
        ) { tab ->
            tabNavigator.saveableState(key = "currentTab", tab) { tab.Content() }
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    /** The bottom bar was removed; kept as a no-op for existing callers in selection screens. */
    suspend fun showBottomNav(show: Boolean) = Unit

    sealed interface Tab {
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
