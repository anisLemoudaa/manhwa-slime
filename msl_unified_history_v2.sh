#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt"

echo "📖 Adding unified manga + novel history to Profile..."

cp "$FILE" "/tmp/ProfileTab.before_unified_history.kt"

python3 - "$FILE" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

if "UnifiedReadingHistorySection(" in s:
    print("ℹ️ Unified history already exists. No duplicate changes made.")
    raise SystemExit(0)

# ---------------------------------------------------------
# Imports
# ---------------------------------------------------------

anchor = "import androidx.compose.material3.Tab as M3Tab\n"

imports = """import coil3.compose.AsyncImage
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.novel.NovelHistoryEntry
import eu.kanade.tachiyomi.novel.NovelHistoryStore
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
"""

if "import eu.kanade.presentation.history.HistoryUiModel" not in s:
    if anchor not in s:
        raise SystemExit("❌ Import anchor not found.")
    s = s.replace(anchor, anchor + imports, 1)

# ---------------------------------------------------------
# History ViewModel + novel history
# ---------------------------------------------------------

state_anchor = """        val viewModel = metroViewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
"""

state_replacement = """        val viewModel = metroViewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()

        val historyViewModel = metroViewModel<HistoryViewModel>()
        val historyState by historyViewModel.state.collectAsState()

        val novelHistory = remember {
            NovelHistoryStore(ctx).all()
        }
"""

if "val historyViewModel = metroViewModel<HistoryViewModel>()" not in s:
    if state_anchor not in s:
        raise SystemExit("❌ Profile state anchor not found.")
    s = s.replace(state_anchor, state_replacement, 1)

# ---------------------------------------------------------
# Add section after reader settings
# ---------------------------------------------------------

section_anchor = """            eu.kanade.tachiyomi.mslime.MslAccountCard()
            eu.kanade.tachiyomi.mslime.MslReaderSettingsCard()
"""

section_replacement = """            eu.kanade.tachiyomi.mslime.MslAccountCard()
            eu.kanade.tachiyomi.mslime.MslReaderSettingsCard()

            UnifiedReadingHistorySection(
                historyState = historyState,
                novelHistory = novelHistory,
            )
"""

if "historyState = historyState" not in s:
    if section_anchor not in s:
        raise SystemExit("❌ Profile reader-settings anchor not found.")
    s = s.replace(section_anchor, section_replacement, 1)

# ---------------------------------------------------------
# Unified history models + UI
# ---------------------------------------------------------

marker = "\ndata object ProfileTab : Tab {\n"

component = r'''
private data class UnifiedReadingItem(
    val title: String,
    val chapter: String,
    val timestamp: Long,
    val type: String,
    val coverUrl: String? = null,
    val mangaCover: tachiyomi.domain.manga.model.MangaCover? = null,
)

private fun unifiedHistoryDate(timestamp: Long): String {
    return runCatching {
        SimpleDateFormat(
            "yyyy/MM/dd HH:mm",
            Locale.getDefault(),
        ).format(Date(timestamp))
    }.getOrDefault("")
}

@Composable
private fun UnifiedReadingHistorySection(
    historyState: HistoryViewModel.State,
    novelHistory: List<NovelHistoryEntry>,
) {
    val mangaItems = historyState.list.orEmpty().mapNotNull { model ->
        val history = (model as? HistoryUiModel.Item)?.item
            ?: return@mapNotNull null

        UnifiedReadingItem(
            title = history.title,
            chapter = if (history.chapterNumber >= 0) {
                "الفصل ${history.chapterNumber}"
            } else {
                "آخر قراءة"
            },
            timestamp = history.readAt?.time ?: 0L,
            type = "مانغا",
            mangaCover = history.coverData,
        )
    }

    val novelItems = novelHistory.map { entry ->
        UnifiedReadingItem(
            title = entry.novelTitle,
            chapter = "الفصل: ${entry.chapterName}",
            timestamp = entry.readAt,
            type = "رواية",
            coverUrl = entry.cover,
        )
    }

    val items = (mangaItems + novelItems)
        .filter { it.timestamp > 0L }
        .sortedByDescending { it.timestamp }
        .take(15)

    if (items.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "📖 سجل القراءة",
            fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            item.mangaCover != null -> {
                                eu.kanade.presentation.manga.components.MangaCover.Book(
                                    modifier = Modifier.size(
                                        width = 48.dp,
                                        height = 68.dp,
                                    ),
                                    data = item.mangaCover,
                                    onClick = {},
                                )
                            }

                            item.coverUrl != null -> {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier
                                        .size(
                                            width = 48.dp,
                                            height = 68.dp,
                                        )
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }

                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(
                                            width = 48.dp,
                                            height = 68.dp,
                                        )
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            MaterialTheme.colorScheme
                                                .secondaryContainer,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "📖",
                                        fontSize = 22.sp,
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = "${item.type} • ${item.chapter}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = unifiedHistoryDate(item.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
'''

if "private data class UnifiedReadingItem" not in s:
    if marker not in s:
        raise SystemExit("❌ Profile declaration marker not found.")
    s = s.replace(marker, "\n" + component + marker, 1)

p.write_text(s.rstrip() + "\n")
PY

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== VERIFY ====="
grep -n \
  "historyViewModel\|historyState\|novelHistory\|UnifiedReadingHistorySection\|UnifiedReadingItem" \
  "$FILE"

echo
echo "===== COMPILE ====="
./gradlew :app:compileDebugKotlin --no-daemon

echo
echo "=========================================="
echo "✅ UNIFIED HISTORY BUILD PASSED"
echo "=========================================="
