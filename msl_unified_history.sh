#!/usr/bin/env bash
set -euo pipefail

PROFILE="app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt"

echo "🐉 Adding unified manga + novel reading history..."

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt")
s = p.read_text()

imports = [
    "import androidx.compose.foundation.lazy.LazyColumn\n",
    "import androidx.compose.foundation.lazy.items\n",
    "import eu.kanade.presentation.history.HistoryUiModel\n",
    "import eu.kanade.tachiyomi.ui.history.HistoryViewModel\n",
    "import eu.kanade.tachiyomi.novel.NovelHistoryEntry\n",
    "import eu.kanade.tachiyomi.novel.NovelHistoryStore\n",
    "import tachiyomi.domain.manga.model.MangaCover\n",
    "import java.text.SimpleDateFormat\n",
    "import java.util.Date\n",
    "import java.util.Locale\n",
]

anchor = "import androidx.compose.material3.Tab as M3Tab\n"

if "eu.kanade.presentation.history.HistoryUiModel" not in s:
    s = s.replace(anchor, anchor + "".join(imports))

anchor = "eu.kanade.tachiyomi.mslime.MslReaderSettingsCard()\n"

block = r'''
            UnifiedHistorySection(
                historyState = historyState,
                novelHistory = novelHistory,
            )
'''

if "UnifiedHistorySection(" not in s:
    s = s.replace(anchor, anchor + block, 1)

anchor = "        val state by viewModel.state.collectAsState()\n"

replacement = """        val state by viewModel.state.collectAsState()
        val historyViewModel = metroViewModel<HistoryViewModel>()
        val historyState by historyViewModel.state.collectAsState()
        val novelHistory = remember(ctx) {
            NovelHistoryStore(ctx).all()
        }
"""

if "val historyViewModel = metroViewModel<HistoryViewModel>()" not in s:
    s = s.replace(anchor, replacement, 1)

marker = "\ndata object ProfileTab : Tab {\n"

component = r'''
private data class UnifiedHistoryRow(
    val title: String,
    val subtitle: String,
    val readAt: Long,
    val mangaCover: MangaCover? = null,
    val isNovel: Boolean = false,
)

private fun historyDate(time: Long): String {
    return runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))
    }.getOrDefault("")
}

@Composable
private fun UnifiedHistorySection(
    historyState: HistoryViewModel.State,
    novelHistory: List<NovelHistoryEntry>,
) {
    val mangaRows = historyState.list.orEmpty().mapNotNull { item ->
        val history = (item as? HistoryUiModel.Item)?.item ?: return@mapNotNull null

        UnifiedHistoryRow(
            title = history.title,
            subtitle = if (history.chapterNumber >= 0) {
                "مانغا • الفصل ${history.chapterNumber}"
            } else {
                "مانغا"
            },
            readAt = history.readAt?.time ?: 0L,
            mangaCover = history.coverData,
            isNovel = false,
        )
    }

    val novelRows = novelHistory.map {
        UnifiedHistoryRow(
            title = it.novelTitle,
            subtitle = "رواية • ${it.chapterName}",
            readAt = it.readAt,
            mangaCover = null,
            isNovel = true,
        )
    }

    val rows = (mangaRows + novelRows)
        .sortedByDescending { it.readAt }
        .take(15)

    if (rows.isEmpty()) return

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
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.mangaCover != null) {
                            eu.kanade.presentation.manga.components.MangaCover.Book(
                                modifier = Modifier.size(width = 52.dp, height = 72.dp),
                                data = row.mangaCover,
                                onClick = {},
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(width = 52.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "رواية",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = row.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                            )

                            Text(
                                text = row.subtitle,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )

                            Text(
                                text = historyDate(row.readAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
'''

if "private data class UnifiedHistoryRow" not in s:
    s = s.replace(marker, component + marker, 1)

p.write_text(s)
PY

echo
echo "===== CHECK ====="
grep -n "historyViewModel\|novelHistory\|UnifiedHistorySection\|UnifiedHistoryRow" "$PROFILE" | head -40

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== STATUS ====="
git status --short

echo
echo "✅ Unified history patch applied."
echo "Next: ./gradlew :app:compileDebugKotlin --no-daemon"
