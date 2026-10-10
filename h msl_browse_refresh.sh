[1mdiff --git a/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt b/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt[m
[1mindex c766947a9..9f01c428c 100644[m
[1m--- a/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt[m
[1m+++ b/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt[m
[36m@@ -14,6 +14,7 @@[m [mimport eu.kanade.tachiyomi.ui.more.DownloadQueueState[m
 import mihon.icons.materialsymbols.MaterialSymbols[m
 import mihon.icons.materialsymbols.automirroredrounded.Help[m
 import mihon.icons.materialsymbols.automirroredrounded.Label[m
[32m+[m[32mimport mihon.icons.materialsymbols.rounded.AccountCircle[m
 import mihon.icons.materialsymbols.rounded.CloudOff[m
 import mihon.icons.materialsymbols.rounded.Download[m
 import mihon.icons.materialsymbols.rounded.Info[m
[36m@@ -31,6 +32,7 @@[m [mimport tachiyomi.presentation.core.i18n.stringResource[m
 @Composable[m
 fun MoreScreen([m
     downloadQueueStateProvider: () -> DownloadQueueState,[m
[32m+[m[32m    onClickProfile: () -> Unit,[m
     downloadedOnly: Boolean,[m
     onDownloadedOnlyChange: (Boolean) -> Unit,[m
     incognitoMode: Boolean,[m
[36m@@ -52,6 +54,14 @@[m [mfun MoreScreen([m
                     iconPadding = PaddingValues(vertical = 32.dp),[m
                 )[m
             }[m
[32m+[m[32m            item {[m
[32m+[m[32m                TextPreferenceWidget([m
[32m+[m[32m                    title = "الملف الشخصي",[m
[32m+[m[32m                    icon = MaterialSymbols.Rounded.AccountCircle,[m
[32m+[m[32m                    onPreferenceClick = onClickProfile,[m
[32m+[m[32m                )[m
[32m+[m[32m            }[m
[32m+[m
             item {[m
                 SwitchPreferenceWidget([m
                     title = stringResource(MR.strings.label_downloaded_only),[m
[1mdiff --git a/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt b/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt[m
[1mindex 74e3f82ef..e8aee7bad 100644[m
[1m--- a/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt[m
[1m+++ b/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt[m
[36m@@ -2,12 +2,33 @@[m [mpackage eu.kanade.tachiyomi.ui.home[m
 [m
 import androidx.activity.compose.BackHandler[m
 import androidx.compose.animation.AnimatedContent[m
[32m+[m[32mimport androidx.compose.animation.AnimatedVisibility[m
[32m+[m[32mimport androidx.compose.animation.animateColorAsState[m
[32m+[m[32mimport androidx.compose.animation.animateDpAsState[m
[32m+[m[32mimport androidx.compose.animation.animateFloatAsState[m
[32m+[m[32mimport androidx.compose.animation.fadeIn[m
[32m+[m[32mimport androidx.compose.animation.fadeOut[m
[32m+[m[32mimport androidx.compose.animation.slideInVertically[m
[32m+[m[32mimport androidx.compose.animation.slideOutVertically[m
 import androidx.compose.animation.togetherWith[m
[32m+[m[32mimport androidx.compose.foundation.background[m
[32m+[m[32mimport androidx.compose.foundation.clickable[m
 import androidx.compose.foundation.layout.Arrangement[m
[32m+[m[32mimport androidx.compose.foundation.layout.Box[m
[32m+[m[32mimport androidx.compose.foundation.layout.Column[m
[32m+[m[32mimport androidx.compose.foundation.layout.fillMaxSize[m
[32m+[m[32mimport androidx.compose.foundation.layout.fillMaxWidth[m
[32m+[m[32mimport androidx.compose.foundation.layout.height[m
[32m+[m[32mimport androidx.compose.foundation.layout.offset[m
[32m+[m[32mimport androidx.compose.foundation.layout.padding[m
[32m+[m[32mimport androidx.compose.foundation.layout.size[m
[32m+[m[32mimport androidx.compose.foundation.shape.RoundedCornerShape[m
 import androidx.compose.material3.Badge[m
 import androidx.compose.material3.Icon[m
 import androidx.compose.material3.NavigationItemColors[m
 import androidx.compose.material3.MaterialTheme[m
[32m+[m[32mimport androidx.compose.material3.Scaffold[m
[32m+[m[32mimport androidx.compose.material3.Surface[m
 import androidx.compose.material3.Text[m
 import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults[m
 import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem[m
[36m@@ -22,11 +43,16 @@[m [mimport androidx.compose.runtime.getValue[m
 import androidx.compose.runtime.produceState[m
 import androidx.compose.runtime.rememberCoroutineScope[m
 import androidx.compose.ui.Modifier[m
[32m+[m[32mimport androidx.compose.ui.draw.clip[m
[32m+[m[32mimport androidx.compose.ui.graphics.Color[m
[32m+[m[32mimport androidx.compose.ui.graphics.graphicsLayer[m
 import androidx.compose.ui.platform.LocalContext[m
 import androidx.compose.ui.semantics.contentDescription[m
 import androidx.compose.ui.semantics.semantics[m
[32m+[m[32mimport androidx.compose.ui.text.font.FontWeight[m
 import androidx.compose.ui.text.style.TextOverflow[m
 import androidx.compose.ui.unit.dp[m
[32m+[m[32mimport androidx.compose.ui.unit.sp[m
 import androidx.compose.ui.util.fastForEach[m
 import cafe.adriel.voyager.navigator.LocalNavigator[m
 import cafe.adriel.voyager.navigator.currentOrThrow[m
[36m@@ -35,9 +61,9 @@[m [mimport cafe.adriel.voyager.navigator.tab.TabNavigator[m
 import eu.kanade.presentation.util.Screen[m
 import eu.kanade.presentation.util.isTabletUi[m
 import eu.kanade.tachiyomi.ui.browse.BrowseTab[m
[32m+[m[32mimport eu.kanade.tachiyomi.ui.profile.ProfileTab[m
 import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen[m
 import eu.kanade.tachiyomi.ui.history.HistoryTab[m
[31m-import eu.kanade.tachiyomi.ui.profile.ProfileTab[m
 import eu.kanade.tachiyomi.ui.library.LibraryTab[m
 import eu.kanade.tachiyomi.ui.manga.MangaScreen[m
 import eu.kanade.tachiyomi.ui.more.MoreTab[m
[36m@@ -89,9 +115,12 @@[m [mobject HomeScreen : Screen() {[m
                     NavigationSuiteType.NavigationBar[m
                 }[m
                 val navigationSuiteState = rememberNavigationSuiteScaffoldState()[m
[32m+[m[32m                var showBottomBar by remember { mutableStateOf(true) }[m
[32m+[m
                 LaunchedEffect(navigationSuiteState, tabletUi) {[m
                     if (tabletUi) navigationSuiteState.show()[m
                     showBottomNavEvent.receiveAsFlow().collectLatest { show ->[m
[32m+[m[32m                        showBottomBar = show[m
                         if (tabletUi || show) {[m
                             navigationSuiteState.show()[m
                         } else {[m
[36m@@ -100,34 +129,46 @@[m [mobject HomeScreen : Screen() {[m
                     }[m
                 }[m
 [m
[31m-                NavigationSuiteScaffold([m
[31m-                    navigationSuiteType = navigationSuiteType,[m
[31m-                    state = navigationSuiteState,[m
[31m-                    navigationSuiteColors = NavigationSuiteDefaults.colors([m
[31m-                        navigationBarContainerColor = MaterialTheme.colorScheme[m
[31m-                            .surfaceColorAtElevation(2.dp),[m
[31m-                        navigationRailContainerColor = MaterialTheme.colorScheme[m
[31m-                            .surfaceColorAtElevation(2.dp),[m
[31m-                        navigationBarContentColor = MaterialTheme.colorScheme.onSurface,[m
[31m-                        navigationRailContentColor = MaterialTheme.colorScheme.onSurface,[m
[31m-                    ),[m
[31m-                    navigationItemVerticalArrangement = Arrangement.Center,[m
[31m-                    navigationItems = {[m
[31m-                        TABS.fastForEach { NavigationSuiteItem(it, navigationSuiteType) }[m
[31m-                    },[m
[31m-                ) {[m
[31m-                    AnimatedContent([m
[31m-                        targetState = tabNavigator.current,[m
[31m-                        transitionSpec = {[m
[31m-                            materialFadeThroughIn([m
[31m-                                initialScale = 1f,[m
[31m-                                durationMillis = TabFadeDuration,[m
[31m-                            ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)[m
[32m+[m[32m                if (tabletUi) {[m
[32m+[m[32m                    NavigationSuiteScaffold([m
[32m+[m[32m                        navigationSuiteType = navigationSuiteType,[m
[32m+[m[32m                        state = navigationSuiteState,[m
[32m+[m[32m                        navigationSuiteColors = NavigationSuiteDefaults.colors([m
[32m+[m[32m                            navigationBarContainerColor = MaterialTheme.colorScheme[m
[32m+[m[32m                                .surfaceColorAtElevation(2.dp),[m
[32m+[m[32m                            navigationRailContainerColor = MaterialTheme.colorScheme[m
[32m+[m[32m                                .surfaceColorAtElevation(2.dp),[m
[32m+[m[32m                            navigationBarContentColor = MaterialTheme.colorScheme.onSurface,[m
[32m+[m[32m                            navigationRailContentColor = MaterialTheme.colorScheme.onSurface,[m
[32m+[m[32m                        ),[m
[32m+[m[32m                        navigationItemVerticalArrangement = Arrangement.Center,[m
[32m+[m[32m                        navigationItems = {[m
[32m+[m[32m                            TABS.fastForEach { NavigationSuiteItem(it, navigationSuiteType) }[m
                         },[m
[31m-                        label = "tabContent",[m
                     ) {[m
[31m-                        tabNavigator.saveableState(key = "currentTab", it) {[m
[31m-                            it.Content()[m
[32m+[m[32m                        HomeTabContent()[m
[32m+[m[32m                    }[m
[32m+[m[32m                } else {[m
[32m+[m[32m                    Scaffold([m
[32m+[m[32m                        bottomBar = {[m
[32m+[m[32m                            AnimatedVisibility([m
[32m+[m[32m                                visible = showBottomBar,[m
[32m+[m[32m                                enter = fadeIn() + slideInVertically { it / 2 },[m
[32m+[m[32m                                exit = fadeOut() + slideOutVertically { it / 2 },[m
[32m+[m[32m                            ) {[m
[32m+[m[32m                                FloatingBottomBar([m
[32m+[m[32m                                    tabs = TABS,[m
[32m+[m[32m                                    navigator = navigator,[m
[32m+[m[32m                                )[m
[32m+[m[32m                            }[m
[32m+[m[32m                        },[m
[32m+[m[32m                    ) { contentPadding ->[m
[32m+[m[32m                        Box([m
[32m+[m[32m                            modifier = Modifier[m
[32m+[m[32m                                .fillMaxSize()[m
[32m+[m[32m                                .padding(contentPadding),[m
[32m+[m[32m                        ) {[m
[32m+[m[32m                            HomeTabContent()[m
                         }[m
                     }[m
                 }[m
[36m@@ -171,6 +212,120 @@[m [mobject HomeScreen : Screen() {[m
         }[m
     }[m
 [m
[32m+[m[32m    @Composable[m
[32m+[m[32m    private fun HomeTabContent() {[m
[32m+[m[32m        val tabNavigator = LocalTabNavigator.current[m
[32m+[m[32m        AnimatedContent([m
[32m+[m[32m            targetState = tabNavigator.current,[m
[32m+[m[32m            transitionSpec = {[m
[32m+[m[32m                materialFadeThroughIn([m
[32m+[m[32m                    initialScale = 1f,[m
[32m+[m[32m                    durationMillis = TabFadeDuration,[m
[32m+[m[32m                ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)[m
[32m+[m[32m            },[m
[32m+[m[32m            label = "tabContent",[m
[32m+[m[32m        ) {[m
[32m+[m[32m            tabNavigator.saveableState(key = "currentTab", it) {[m
[32m+[m[32m                it.Content()[m
[32m+[m[32m            }[m
[32m+[m[32m        }[m
[32m+[m[32m    }[m
[32m+[m
[32m+[m[32m    @Composable[m
[32m+[m[32m    private fun FloatingBottomBar([m
[32m+[m[32m        tabs: List<eu.kanade.presentation.util.Tab>,[m
[32m+[m[32m        navigator: cafe.adriel.voyager.navigator.Navigator,[m
[32m+[m[32m    ) {[m
[32m+[m[32m        val tabNavigator = LocalTabNavigator.current[m
[32m+[m[32m        val scope = rememberCoroutineScope()[m
[32m+[m
[32m+[m[32m        Surface([m
[32m+[m[32m            modifier = Modifier[m
[32m+[m[32m                .fillMaxWidth()[m
[32m+[m[32m                .padding(horizontal = 12.dp, vertical = 10.dp),[m
[32m+[m[32m            shape = RoundedCornerShape(28.dp),[m
[32m+[m[32m            color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),[m
[32m+[m[32m            tonalElevation = 6.dp,[m
[32m+[m[32m            shadowElevation = 8.dp,[m
[32m+[m[32m        ) {[m
[32m+[m[32m            Row([m
[32m+[m[32m                modifier = Modifier[m
[32m+[m[32m                    .fillMaxWidth()[m
[32m+[m[32m                    .height(68.dp)[m
[32m+[m[32m                    .padding(6.dp),[m
[32m+[m[32m                horizontalArrangement = Arrangement.spacedBy(4.dp),[m
[32m+[m[32m            ) {[m
[32m+[m[32m                tabs.fastForEach { tab ->[m
[32m+[m[32m                    val selected = tabNavigator.current::class == tab::class[m
[32m+[m[32m                    val backgroundColor by animateColorAsState([m
[32m+[m[32m                        targetValue = if (selected) {[m
[32m+[m[32m                            MaterialTheme.colorScheme.primaryContainer[m
[32m+[m[32m                        } else {[m
[32m+[m[32m                            Color.Transparent[m
[32m+[m[32m                        },[m
[32m+[m[32m                        label = "navBackground",[m
[32m+[m[32m                    )[m
[32m+[m[32m                    val iconScale by animateFloatAsState([m
[32m+[m[32m                        targetValue = if (selected) 1.08f else 0.96f,[m
[32m+[m[32m                        label = "navIconScale",[m
[32m+[m[32m                    )[m
[32m+[m[32m                    val offsetY by animateDpAsState([m
[32m+[m[32m                        targetValue = if (selected) (-2).dp else 0.dp,[m
[32m+[m[32m                        label = "navIconOffset",[m
[32m+[m[32m                    )[m
[32m+[m
[32m+[m[32m                    Column([m
[32m+[m[32m                        modifier = Modifier[m
[32m+[m[32m                            .weight(1f)[m
[32m+[m[32m                            .fillMaxSize()[m
[32m+[m[32m                            .clip(RoundedCornerShape(22.dp))[m
[32m+[m[32m                            .background(backgroundColor)[m
[32m+[m[32m                            .clickable {[m
[32m+[m[32m                                if (selected) {[m
[32m+[m[32m                                    scope.launch { tab.onReselect(navigator) }[m
[32m+[m[32m                                } else {[m
[32m+[m[32m                                    tabNavigator.current = tab[m
[32m+[m[32m                                }[m
[32m+[m[32m                            }[m
[32m+[m[32m                            .padding(horizontal = 4.dp, vertical = 4.dp),[m
[32m+[m[32m                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,[m
[32m+[m[32m                        verticalArrangement = Arrangement.Center,[m
[32m+[m[32m                    ) {[m
[32m+[m[32m                        Icon([m
[32m+[m[32m                            painter = tab.options.icon!!,[m
[32m+[m[32m                            contentDescription = tab.options.title,[m
[32m+[m[32m                            modifier = Modifier[m
[32m+[m[32m                                .size(24.dp)[m
[32m+[m[32m                                .graphicsLayer {[m
[32m+[m[32m                                    scaleX = iconScale[m
[32m+[m[32m                                    scaleY = iconScale[m
[32m+[m[32m                                }[m
[32m+[m[32m                                .offset(y = offsetY),[m
[32m+[m[32m                            tint = if (selected) {[m
[32m+[m[32m                                MaterialTheme.colorScheme.onPrimaryContainer[m
[32m+[m[32m                            } else {[m
[32m+[m[32m                                MaterialTheme.colorScheme.onSurfaceVariant[m
[32m+[m[32m                            },[m
[32m+[m[32m                        )[m
[32m+[m[32m                        Text([m
[32m+[m[32m                            text = tab.options.title,[m
[32m+[m[32m                            style = MaterialTheme.typography.labelLarge,[m
[32m+[m[32m                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,[m
[32m+[m[32m                            color = if (selected) {[m
[32m+[m[32m                                MaterialTheme.colorScheme.primary[m
[32m+[m[32m                            } else {[m
[32m+[m[32m                                MaterialTheme.colorScheme.onSurfaceVariant[m
[32m+[m[32m                            },[m
[32m+[m[32m                            fontSize = 11.sp,[m
[32m+[m[32m                            maxLines = 1,[m
[32m+[m[32m                            overflow = TextOverflow.Ellipsis,[m
[32m+[m[32m                        )[m
[32m+[m[32m                    }[m
[32m+[m[32m                }[m
[32m+[m[32m            }[m
[32m+[m[32m        }[m
[32m+[m[32m    }[m
[32m+[m
     @Composable[m
     private fun NavigationSuiteItem([m
         tab: eu.kanade.presentation.util.Tab,[m
[1mdiff --git a/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt b/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt[m
[1mindex f474296e6..6cc6cd922 100644[m
[1m--- a/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt[m
[1m+++ b/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt[m
[36m@@ -27,6 +27,7 @@[m [mimport eu.kanade.tachiyomi.R[m
 import eu.kanade.tachiyomi.data.download.DownloadManager[m
 import eu.kanade.tachiyomi.ui.category.CategoryScreen[m
 import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen[m
[32m+[m[32mimport eu.kanade.tachiyomi.ui.profile.ProfileTab[m
 import eu.kanade.tachiyomi.ui.setting.SettingsScreen[m
 import eu.kanade.tachiyomi.ui.stats.StatsScreen[m
 import kotlinx.coroutines.flow.MutableStateFlow[m
[36m@@ -60,6 +61,7 @@[m [mdata object MoreTab : Tab {[m
     @Composable[m
     override fun Content() {[m
         val navigator = LocalNavigator.currentOrThrow[m
[32m+[m[32m        val tabNavigator = LocalTabNavigator.current[m
         val viewModel = metroViewModel<MoreViewModel>()[m
         val downloadQueueState by viewModel.downloadQueueState.collectAsState()[m
         MoreScreen([m
[36m@@ -68,6 +70,7 @@[m [mdata object MoreTab : Tab {[m
             onDownloadedOnlyChange = { viewModel.downloadedOnly = it },[m
             incognitoMode = viewModel.incognitoMode,[m
             onIncognitoModeChange = { viewModel.incognitoMode = it },[m
[32m+[m[32m            onClickProfile = { tabNavigator.current = ProfileTab },[m
             onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },[m
             onClickCategories = { navigator.push(CategoryScreen()) },[m
             onClickStats = { navigator.push(StatsScreen()) },[m
