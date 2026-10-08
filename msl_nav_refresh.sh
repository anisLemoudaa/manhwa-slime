#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HOME="app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"
MORE_TAB="app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt"
MORE_SCREEN="app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt"

python3 - <<'PY'
from pathlib import Path

HOME = Path("app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt")
MORE_TAB = Path("app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt")
MORE_SCREEN = Path("app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"[ERROR] Could not find {label}. The file may already be changed or differ from ui-refresh.")
    return text.replace(old, new, 1)

# ---------------- HomeScreen.kt ----------------
s = HOME.read_text(encoding="utf-8")

s = replace_once(
    s,
    """import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith""",
    """import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateDpAsState
import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith""",
    "HomeScreen animation imports",
)

s = replace_once(
    s,
    """import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Badge""",
    """import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge""",
    "HomeScreen layout imports",
)

s = replace_once(
    s,
    """import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text""",
    """import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text""",
    "HomeScreen Material3 imports",
)

s = replace_once(
    s,
    """import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext""",
    """import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext""",
    "HomeScreen graphics imports",
)

s = replace_once(
    s,
    """import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp""",
    """import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp""",
    "HomeScreen typography imports",
)

s = s.replace("import eu.kanade.tachiyomi.ui.profile.ProfileTab\n", "", 1)
s = s.replace("import eu.kanade.tachiyomi.ui.browse.BrowseTab\n", "import eu.kanade.tachiyomi.ui.browse.BrowseTab\nimport eu.kanade.tachiyomi.ui.profile.ProfileTab\n", 1)

s = replace_once(
    s,
    """    private val TABS = listOf(
        LibraryTab,
        UpdatesTab,
        ProfileTab,
        BrowseTab,
        MoreTab,
    )""",
    """    private val TABS = listOf(
        LibraryTab,
        UpdatesTab,
        ProfileTab,
        BrowseTab,
        MoreTab,
    )""",
    "four bottom tabs",
)

s = replace_once(
    s,
    """                val navigationSuiteState = rememberNavigationSuiteScaffoldState()
                LaunchedEffect(navigationSuiteState, tabletUi) {""",
    """                val navigationSuiteState = rememberNavigationSuiteScaffoldState()
                var showBottomBar by remember { mutableStateOf(true) }

                LaunchedEffect(navigationSuiteState, tabletUi) {""",
    "bottom bar visibility state",
)

s = replace_once(
    s,
    """                    showBottomNavEvent.receiveAsFlow().collectLatest { show ->
                        if (tabletUi || show) {
                            navigationSuiteState.show()
                        } else {
                            navigationSuiteState.hide()
                        }
                    }""",
    """                    showBottomNavEvent.receiveAsFlow().collectLatest { show ->
                        showBottomBar = show
                        if (tabletUi || show) {
                            navigationSuiteState.show()
                        } else {
                            navigationSuiteState.hide()
                        }
                    }""",
    "bottom bar visibility event",
)

old_nav = """                NavigationSuiteScaffold(
                    navigationSuiteType = navigationSuiteType,
                    state = navigationSuiteState,
                    navigationSuiteColors = NavigationSuiteDefaults.colors(
                        navigationBarContainerColor = MaterialTheme.colorScheme
                            .surfaceColorAtElevation(2.dp),
                        navigationRailContainerColor = MaterialTheme.colorScheme
                            .surfaceColorAtElevation(2.dp),
                        navigationBarContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationRailContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    navigationItemVerticalArrangement = Arrangement.Center,
                    navigationItems = {
                        TABS.fastForEach { NavigationSuiteItem(it, navigationSuiteType) }
                    },
                ) {
                    AnimatedContent(
                        targetState = tabNavigator.current,
                        transitionSpec = {
                            materialFadeThroughIn(
                                initialScale = 1f,
                                durationMillis = TabFadeDuration,
                            ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)
                        },
                        label = "tabContent",
                    ) {
                        tabNavigator.saveableState(key = "currentTab", it) {
                            it.Content()
                        }
                    }
                }"""

new_nav = """                if (tabletUi) {
                    NavigationSuiteScaffold(
                        navigationSuiteType = navigationSuiteType,
                        state = navigationSuiteState,
                        navigationSuiteColors = NavigationSuiteDefaults.colors(
                            navigationBarContainerColor = MaterialTheme.colorScheme
                                .surfaceColorAtElevation(2.dp),
                            navigationRailContainerColor = MaterialTheme.colorScheme
                                .surfaceColorAtElevation(2.dp),
                            navigationBarContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationRailContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationItemVerticalArrangement = Arrangement.Center,
                        navigationItems = {
                            TABS.fastForEach { NavigationSuiteItem(it, navigationSuiteType) }
                        },
                    ) {
                        HomeTabContent()
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            AnimatedVisibility(
                                visible = showBottomBar,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                            ) {
                                FloatingBottomBar(
                                    tabs = TABS,
                                    navigator = navigator,
                                )
                            }
                        },
                    ) { contentPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                        ) {
                            HomeTabContent()
                        }
                    }
                }"""

s = replace_once(s, old_nav, new_nav, "NavigationSuiteScaffold phone/tablet layout")

marker = """    @Composable
    private fun NavigationSuiteItem(
"""
helpers = """    @Composable
    private fun HomeTabContent() {
        val tabNavigator = LocalTabNavigator.current
        AnimatedContent(
            targetState = tabNavigator.current,
            transitionSpec = {
                materialFadeThroughIn(
                    initialScale = 1f,
                    durationMillis = TabFadeDuration,
                ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)
            },
            label = "tabContent",
        ) {
            tabNavigator.saveableState(key = "currentTab", it) {
                it.Content()
            }
        }
    }

    @Composable
    private fun FloatingBottomBar(
        tabs: List<eu.kanade.presentation.util.Tab>,
        navigator: cafe.adriel.voyager.navigator.Navigator,
    ) {
        val tabNavigator = LocalTabNavigator.current
        val scope = rememberCoroutineScope()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.fastForEach { tab ->
                    val selected = tabNavigator.current::class == tab::class
                    val backgroundColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        label = "navBackground",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 0.96f,
                        label = "navIconScale",
                    )
                    val offsetY by animateDpAsState(
                        targetValue = if (selected) (-2).dp else 0.dp,
                        label = "navIconOffset",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .background(backgroundColor)
                            .clickable {
                                if (selected) {
                                    scope.launch { tab.onReselect(navigator) }
                                } else {
                                    tabNavigator.current = tab
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = tab.options.icon!!,
                            contentDescription = tab.options.title,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                                .offset(y = offsetY),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = tab.options.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

"""
s = replace_once(s, marker, helpers + marker, "new floating navigation components")

HOME.write_text(s, encoding="utf-8")

# ---------------- MoreTab.kt ----------------
s = MORE_TAB.read_text(encoding="utf-8")
s = replace_once(
    s,
    "import eu.kanade.tachiyomi.ui.more.MoreTab\n" if False else "import eu.kanade.tachiyomi.ui.setting.SettingsScreen\n",
    "import eu.kanade.tachiyomi.ui.profile.ProfileTab\nimport eu.kanade.tachiyomi.ui.setting.SettingsScreen\n",
    "ProfileTab import",
)
s = replace_once(
    s,
    """        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<MoreViewModel>()""",
    """        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val viewModel = metroViewModel<MoreViewModel>()""",
    "MoreTab tab navigator",
)
s = replace_once(
    s,
    """            onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },
            onClickCategories = { navigator.push(CategoryScreen()) },""",
    """            onClickProfile = { tabNavigator.current = ProfileTab },
            onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },
            onClickCategories = { navigator.push(CategoryScreen()) },""",
    "profile callback",
)
MORE_TAB.write_text(s, encoding="utf-8")

# ---------------- MoreScreen.kt ----------------
s = MORE_SCREEN.read_text(encoding="utf-8")
s = replace_once(
    s,
    "import mihon.icons.materialsymbols.rounded.CloudOff\n",
    "import mihon.icons.materialsymbols.rounded.AccountCircle\nimport mihon.icons.materialsymbols.rounded.CloudOff\n",
    "profile icon import",
)
s = replace_once(
    s,
    """    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,""",
    """    downloadQueueStateProvider: () -> DownloadQueueState,
    onClickProfile: () -> Unit,
    downloadedOnly: Boolean,""",
    "MoreScreen profile parameter",
)
s = replace_once(
    s,
    """            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.label_downloaded_only),""",
    """            item {
                TextPreferenceWidget(
                    title = "الملف الشخصي",
                    icon = MaterialSymbols.Rounded.AccountCircle,
                    onPreferenceClick = onClickProfile,
                )
            }

            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.label_downloaded_only),""",
    "profile item",
)
MORE_SCREEN.write_text(s, encoding="utf-8")

print("Navigation refresh applied.")
print("Changed:")
print(f"  - {HOME}")
print(f"  - {MORE_TAB}")
print(f"  - {MORE_SCREEN}")
PY

echo
echo "Review the diff:"
git diff -- "$HOME" "$MORE_TAB" "$MORE_SCREEN"
echo
echo "Next build command:"
echo './gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" -Dorg.gradle.parallel=false -Dorg.gradle.tooling.parallel=false -Dkotlin.daemon.jvm.options="-Xmx1536m" assembleDebug'
