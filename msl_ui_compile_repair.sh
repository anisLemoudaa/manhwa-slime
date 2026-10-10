#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HOME_FILE="app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt"
SOURCES_FILE="app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt"
MORE_FILE="app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt"

for f in "$HOME_FILE" "$SOURCES_FILE" "$MORE_FILE"; do
    test -f "$f" || { echo "ERROR: missing $f"; exit 1; }
done

# Restore only the three files involved in the broken patch.
git checkout -- "$HOME_FILE" "$SOURCES_FILE" "$MORE_FILE"

python3 - "$HOME_FILE" "$SOURCES_FILE" <<'PY'
from pathlib import Path
import sys

home = Path(sys.argv[1])
sources = Path(sys.argv[2])

def add_import(text: str, anchor: str, new_import: str) -> str:
    if new_import in text:
        return text
    if anchor not in text:
        raise SystemExit(f"ERROR: import anchor not found: {anchor}")
    return text.replace(anchor, anchor + "\n" + new_import, 1)

# ---------- HomeScreen ----------
h = home.read_text(encoding="utf-8")

imports = [
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.AnimatedVisibility"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.fadeIn"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.fadeOut"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.slideInVertically"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.slideOutVertically"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.core.animateColorAsState"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.core.animateDpAsState"),
    ("import androidx.compose.animation.AnimatedContent", "import androidx.compose.animation.core.animateFloatAsState"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.background"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.clickable"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.Box"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.Column"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.Row"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.fillMaxSize"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.fillMaxWidth"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.height"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.offset"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.padding"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.layout.size"),
    ("import androidx.compose.foundation.layout.Arrangement", "import androidx.compose.foundation.shape.RoundedCornerShape"),
    ("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Alignment"),
    ("import androidx.compose.ui.Modifier", "import androidx.compose.ui.draw.clip"),
    ("import androidx.compose.ui.Modifier", "import androidx.compose.ui.graphics.Color"),
    ("import androidx.compose.ui.Modifier", "import androidx.compose.ui.graphics.graphicsLayer"),
    ("import androidx.compose.ui.Modifier", "import androidx.compose.ui.text.font.FontWeight"),
    ("import androidx.compose.ui.unit.dp", "import androidx.compose.ui.unit.sp"),
    ("import androidx.compose.runtime.LaunchedEffect", "import androidx.compose.runtime.mutableStateOf"),
    ("import androidx.compose.runtime.LaunchedEffect", "import androidx.compose.runtime.remember"),
    ("import androidx.compose.runtime.LaunchedEffect", "import androidx.compose.runtime.setValue"),
    ("import androidx.compose.material3.Icon", "import androidx.compose.material3.Scaffold"),
    ("import androidx.compose.material3.Icon", "import androidx.compose.material3.Surface"),
]
for anchor, imp in imports:
    h = add_import(h, anchor, imp)

# Never import Modifier.weight directly. It is resolved inside RowScope.
h = h.replace("import androidx.compose.foundation.layout.weight\n", "")

start_marker = "                val tabletUi = isTabletUi()"
end_marker = "            val goToLibraryTab = { tabNavigator.current = LibraryTab }"
start = h.find(start_marker)
end = h.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("ERROR: HomeScreen main content section not found")

main = """                val tabletUi = isTabletUi()
                var showBottomBar by remember { mutableStateOf(true) }

                LaunchedEffect(tabletUi) {
                    showBottomNavEvent.receiveAsFlow().collectLatest { show ->
                        showBottomBar = show
                    }
                }

                if (tabletUi) {
                    NavigationSuiteScaffold(
                        navigationSuiteType = NavigationSuiteType.NavigationRail,
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
                            TABS.fastForEach {
                                NavigationSuiteItem(it, NavigationSuiteType.NavigationRail)
                            }
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
                }
            }

"""
h = h[:start] + main + h[end:]

insert_marker = "    @Composable\n    private fun NavigationSuiteItem("
idx = h.find(insert_marker)
if idx < 0:
    raise SystemExit("ERROR: NavigationSuiteItem insertion point not found")

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
                        horizontalAlignment = Alignment.CenterHorizontally,
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
h = h[:idx] + helpers + h[idx:]

home.write_text(h, encoding="utf-8")

# ---------- SourcesScreen ----------
s = sources.read_text(encoding="utf-8")

for anchor, imp in [
    ("import androidx.compose.foundation.layout.Column", "import androidx.compose.foundation.layout.Row"),
    ("import androidx.compose.foundation.layout.Row", "import androidx.compose.foundation.layout.Spacer"),
    ("import androidx.compose.foundation.layout.Spacer", "import androidx.compose.foundation.layout.width"),
    ("import androidx.compose.material3.IconButton", "import androidx.compose.material3.HorizontalDivider"),
    ("import androidx.compose.runtime.Composable", "import androidx.compose.ui.Alignment"),
    ("import androidx.compose.ui.platform.LocalContext", "import androidx.compose.ui.text.font.FontWeight"),
]:
    s = add_import(s, anchor, imp)

old_padding = "contentPadding = contentPadding + topSmallPaddingValues,"
new_padding = """contentPadding = contentPadding + PaddingValues(
                    start = 8.dp,
                    top = 12.dp,
                    end = 8.dp,
                    bottom = 16.dp,
                ) + topSmallPaddingValues,"""
if old_padding not in s:
    raise SystemExit("ERROR: SourcesScreen contentPadding line not found")
s = s.replace(old_padding, new_padding, 1)

old_header = """    val context = LocalContext.current
    Text(
        text = LocaleHelper.getSourceDisplayName(language, context),
        modifier = modifier
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        style = MaterialTheme.typography.header,
    )"""
new_header = """    val context = LocalContext.current
    Row(
        modifier = modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = 10.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LocaleHelper.getSourceDisplayName(language, context),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }"""
if old_header not in s:
    raise SystemExit("ERROR: original SourceHeader block not found")
s = s.replace(old_header, new_header, 1)

sources.write_text(s, encoding="utf-8")
PY

echo
echo "✅ Clean repair applied."
echo
git diff --check
echo
echo "Changed files:"
git diff --name-only
echo
echo "No commit or push was performed."
