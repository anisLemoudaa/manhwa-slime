#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"

echo "📖 Upgrading novel reader..."

cp "$FILE" "/tmp/NovelSectionScreen.kt.backup"

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt")
s = p.read_text()

# ------------------------------------------------------------
# 1. Make chapter list indexed and pass the complete chapter list
# ------------------------------------------------------------

if "itemsIndexed(d.chapters.orEmpty())" not in s:
    s = s.replace(
        "items(d.chapters.orEmpty()) { chapter ->",
        "itemsIndexed(d.chapters.orEmpty()) { index, chapter ->",
        1,
    )

old = """navigator.push(NovelReaderScreen(sourceId, chapter.path, chapter.name))"""

new = """navigator.push(
                                    NovelReaderScreen(
                                        sourceId = sourceId,
                                        novelPath = d.path,
                                        novelName = d.name ?: fallbackName,
                                        novelCover = d.cover,
                                        chapters = d.chapters.orEmpty(),
                                        chapterIndex = index,
                                    ),
                                )"""

if old in s:
    s = s.replace(old, new, 1)

# Handle the version produced by the previous history patch.
old2 = """NovelReaderScreen(
                                    sourceId = sourceId,
                                    novelPath = d.path,
                                    novelName = d.name ?: fallbackName,
                                    novelCover = d.cover,
                                    chapterPath = chapter.path,
                                    chapterName = chapter.name,
                                    chapterIndex = index,
                                    totalChapters = d.chapters?.size ?: 0,
                                )"""

new2 = """NovelReaderScreen(
                                    sourceId = sourceId,
                                    novelPath = d.path,
                                    novelName = d.name ?: fallbackName,
                                    novelCover = d.cover,
                                    chapters = d.chapters.orEmpty(),
                                    chapterIndex = index,
                                )"""

if old2 in s:
    s = s.replace(old2, new2, 1)

# ------------------------------------------------------------
# 2. Replace the reader with a persistent next/previous reader
# ------------------------------------------------------------

start = s.find("class NovelReaderScreen(")

if start == -1:
    raise SystemExit("ERROR: NovelReaderScreen was not found.")

new_reader = r'''class NovelReaderScreen(
    private val sourceId: String,
    private val novelPath: String,
    private val novelName: String,
    private val novelCover: String?,
    private val chapters: List<NovelChapter>,
    private val chapterIndex: Int,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val historyStore = remember { NovelHistoryStore(context) }
        val isDark = isSystemInDarkTheme()

        var currentIndex by remember {
            mutableIntStateOf(
                chapterIndex.coerceIn(
                    0,
                    (chapters.size - 1).coerceAtLeast(0),
                ),
            )
        }

        var html by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }

        val currentChapter = chapters.getOrNull(currentIndex)

        fun loadChapter(index: Int) {
            if (index !in chapters.indices) return

            currentIndex = index
            html = null
            error = null
            loading = true

            val chapter = chapters[index]

            LaunchedEffectKey.trigger(
                key = "$sourceId:${chapter.path}",
            ) {
                val result = runCatching {
                    manager.chapter(sourceId, chapter.path)
                }

                html = result.getOrNull()
                error = result.exceptionOrNull()?.message
                loading = false

                if (result.isSuccess) {
                    historyStore.upsert(
                        NovelHistoryEntry(
                            sourceId = sourceId,
                            novelPath = novelPath,
                            novelTitle = novelName,
                            cover = novelCover,
                            chapterPath = chapter.path,
                            chapterName = chapter.name,
                            chapterNumber = chapter.chapterNumber,
                            chapterIndex = index,
                            totalChapters = chapters.size,
                        ),
                    )
                }
            }
        }

        LaunchedEffect(currentIndex) {
            val chapter = chapters.getOrNull(currentIndex) ?: return@LaunchedEffect

            html = null
            error = null
            loading = true

            val result = runCatching {
                manager.chapter(sourceId, chapter.path)
            }

            html = result.getOrNull()
            error = result.exceptionOrNull()?.message
            loading = false

            if (result.isSuccess) {
                historyStore.upsert(
                    NovelHistoryEntry(
                        sourceId = sourceId,
                        novelPath = novelPath,
                        novelTitle = novelName,
                        cover = novelCover,
                        chapterPath = chapter.path,
                        chapterName = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        chapterIndex = currentIndex,
                        totalChapters = chapters.size,
                    ),
                )
            }
        }

        val hasPrevious = currentIndex > 0
        val hasNext = currentIndex < chapters.lastIndex

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                novelName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "الفصل ${currentIndex + 1} من ${chapters.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Text("‹", fontSize = 32.sp)
                        }
                    },
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (hasPrevious) {
                                    currentIndex--
                                }
                            },
                            enabled = hasPrevious && !loading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("‹ السابق")
                        }

                        Text(
                            "${currentIndex + 1}/${chapters.size}",
                            modifier = Modifier.padding(horizontal = 6.dp),
                            fontWeight = FontWeight.Bold,
                        )

                        Button(
                            onClick = {
                                if (hasNext) {
                                    currentIndex++
                                }
                            },
                            enabled = hasNext && !loading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("التالي ›")
                        }
                    }
                }
            },
        ) { padding ->

            when {
                loading -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "جاري تحميل الفصل...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                error != null -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                error ?: "حدث خطأ",
                                color = MaterialTheme.colorScheme.error,
                            )

                            Button(
                                onClick = {
                                    currentIndex = currentIndex
                                },
                            ) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }
                }

                html != null -> {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        factory = {
                            WebView(it).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                            }
                        },
                        update = { webView ->
                            val bg =
                                if (isDark) "#111111" else "#ffffff"
                            val fg =
                                if (isDark) "#eeeeee" else "#171717"

                            val style = """
                                <style>
                                body {
                                    font-family: sans-serif;
                                    font-size: 19px;
                                    line-height: 1.95;
                                    padding: 20px;
                                    margin: 0;
                                    background: $bg;
                                    color: $fg;
                                }

                                img {
                                    max-width: 100%;
                                    height: auto;
                                }

                                a {
                                    color: inherit;
                                }

                                p {
                                    margin: 0 0 1.1em 0;
                                }
                                </style>
                            """.trimIndent()

                            webView.loadDataWithBaseURL(
                                null,
                                style + html.orEmpty(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        },
                    )
                }

                else -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        Alignment.Center,
                    )
                }
            }
        }
    }
}

private object LaunchedEffectKey {
    @Composable
    fun trigger(
        key: String,
        block: suspend () -> Unit,
    ) {
        LaunchedEffect(key) {
            block()
        }
    }
}
'''

s = s[:start] + new_reader + "\n"

p.write_text(s)
PY

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== READER CHECK ====="
grep -n \
  "itemsIndexed\|NovelReaderScreen\|hasPrevious\|hasNext\|الفصل.*من\|السابق\|التالي\|NovelHistoryEntry" \
  "$FILE" | tail -80

echo
echo "===== STATUS ====="
git status --short

echo
echo "✅ Reader navigation patch applied."
echo
echo "Now run:"
echo "./gradlew :app:compileDebugKotlin --no-daemon"
