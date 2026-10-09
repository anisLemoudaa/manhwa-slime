#!/usr/bin/env bash
set -euo pipefail

ROOT="/workspaces/manhwa-slime"
cd "$ROOT"

mkdir -p app/src/main/java/eu/kanade/tachiyomi/novel

cat > app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt <<'KOTLIN'
package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NovelHistoryEntry(
    val sourceId: String,
    val novelPath: String,
    val novelTitle: String,
    val cover: String? = null,
    val chapterPath: String,
    val chapterName: String,
    val chapterNumber: Double? = null,
    val chapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val readAt: Long = System.currentTimeMillis(),
)

class NovelHistoryStore(context: Context) {
    private val file =
        File(context.applicationContext.filesDir, "msl_novel_history.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun all(): List<NovelHistoryEntry> {
        if (!file.exists()) return emptyList()

        return runCatching {
            json.decodeFromString<List<NovelHistoryEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(entry: NovelHistoryEntry) {
        val updated = all()
            .filterNot {
                it.sourceId == entry.sourceId &&
                    it.chapterPath == entry.chapterPath
            }
            .toMutableList()
            .apply {
                add(
                    0,
                    entry.copy(
                        readAt = System.currentTimeMillis(),
                    ),
                )
            }
            .take(MAX_ENTRIES)

        file.writeText(json.encodeToString(updated))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val MAX_ENTRIES = 100
    }
}
KOTLIN

python3 <<'PY'
from pathlib import Path

p = Path("app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt")
s = p.read_text()

# 1. Add itemsIndexed import.
if "import androidx.compose.foundation.lazy.itemsIndexed" not in s:
    s = s.replace(
        "import androidx.compose.foundation.lazy.items",
        "import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.itemsIndexed",
    )

# 2. Pass novel information and chapter index to the reader.
old = """                    items(d.chapters.orEmpty()) { chapter ->
                        ListItem(
                            modifier = Modifier.clickable {
                                navigator.push(NovelReaderScreen(sourceId, chapter.path, chapter.name))
                            },
"""
new = """                    itemsIndexed(d.chapters.orEmpty()) { index, chapter ->
                        ListItem(
                            modifier = Modifier.clickable {
                                navigator.push(
                                    NovelReaderScreen(
                                        sourceId = sourceId,
                                        novelPath = d.path,
                                        novelName = d.name ?: fallbackName,
                                        novelCover = d.cover,
                                        chapterPath = chapter.path,
                                        chapterName = chapter.name,
                                        chapterIndex = index,
                                        totalChapters = d.chapters?.size ?: 0,
                                    ),
                                )
                            },
"""
if old not in s:
    raise SystemExit("Could not find novel chapter list block.")
s = s.replace(old, new)

# 3. Replace reader constructor.
old = """class NovelReaderScreen(
    private val sourceId: String,
    private val chapterPath: String,
    private val chapterName: String,
) : Screen() {
"""
new = """class NovelReaderScreen(
    private val sourceId: String,
    private val novelPath: String,
    private val novelName: String,
    private val novelCover: String?,
    private val chapterPath: String,
    private val chapterName: String,
    private val chapterIndex: Int,
    private val totalChapters: Int,
) : Screen() {
"""
if old not in s:
    raise SystemExit("Could not find NovelReaderScreen constructor.")
s = s.replace(old, new)

# 4. Add history store to reader.
old = """        val manager = remember { NovelManagerHolder.get(context) }
        val isDark = isSystemInDarkTheme()
"""
new = """        val manager = remember { NovelManagerHolder.get(context) }
        val historyStore = remember { NovelHistoryStore(context) }
        val isDark = isSystemInDarkTheme()
"""
if old not in s:
    raise SystemExit("Could not find reader manager block.")
s = s.replace(old, new)

# 5. Save history only after chapter content loads successfully.
old = """        LaunchedEffect(Unit) {
            val r = runCatching { manager.chapter(sourceId, chapterPath) }
            html = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
"""
new = """        LaunchedEffect(Unit) {
            val r = runCatching { manager.chapter(sourceId, chapterPath) }
            html = r.getOrNull()
            error = r.exceptionOrNull()?.message

            if (r.isSuccess && !r.getOrNull().isNullOrBlank()) {
                historyStore.upsert(
                    NovelHistoryEntry(
                        sourceId = sourceId,
                        novelPath = novelPath,
                        novelTitle = novelName,
                        cover = novelCover,
                        chapterPath = chapterPath,
                        chapterName = chapterName,
                        chapterNumber = null,
                        chapterIndex = chapterIndex,
                        totalChapters = totalChapters,
                    ),
                )
            }
        }
"""
if old not in s:
    raise SystemExit("Could not find reader loading block.")
s = s.replace(old, new)

p.write_text(s)
PY

git diff --check

printf '\n===== NOVEL HISTORY =====\n'
sed -n '1,220p' app/src/main/java/eu/kanade/tachiyomi/novel/NovelHistoryStore.kt

printf '\n===== NOVEL READER CHECK =====\n'
grep -nE 'NovelHistoryStore|NovelHistoryEntry|itemsIndexed|novelPath|chapterIndex' \
  app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt

printf '\n===== STATUS =====\n'
git status --short
