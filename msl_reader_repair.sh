#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"
BACKUP="/tmp/NovelSectionScreen.kt.backup"

echo "🛠️ Repairing NovelReaderScreen..."

if [ ! -f "$BACKUP" ]; then
    echo "❌ Backup not found: $BACKUP"
    echo "Aborting to protect the project."
    exit 1
fi

cp "$BACKUP" "$FILE"

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt")
s = p.read_text()

# ------------------------------------------------------------
# 1. Use indexed chapters
# ------------------------------------------------------------

s = s.replace(
    "items(d.chapters.orEmpty()) { chapter ->",
    "itemsIndexed(d.chapters.orEmpty()) { index, chapter ->",
    1,
)

# ------------------------------------------------------------
# 2. Replace the reader call with the correct constructor
# ------------------------------------------------------------

old = """NovelReaderScreen(
    sourceId = sourceId,
    novelPath = d.path,
    novelName = d.name ?: fallbackName,
    novelCover = d.cover,
    chapterPath = chapter.path,
    chapterName = chapter.name,
    chapterIndex = index,
    totalChapters = d.chapters?.size ?: 0,
)"""

new = """NovelReaderScreen(
    sourceId = sourceId,
    novelPath = d.path,
    novelName = d.name ?: fallbackName,
    novelCover = d.cover,
    chapters = d.chapters.orEmpty(),
    chapterIndex = index,
)"""

if old in s:
    s = s.replace(old, new, 1)

# ------------------------------------------------------------
# 3. Replace reader class only
# ------------------------------------------------------------

start = s.find("class NovelReaderScreen(")

if start == -1:
    raise SystemExit("❌ NovelReaderScreen not found")

reader = r'''class NovelReaderScreen(
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

        LaunchedEffect(currentIndex, sourceId) {
            val chapter = chapters.getOrNull(currentIndex)

            if (chapter == null) {
                error = "الفصل غير موجود"
                loading = false
                return@LaunchedEffect
            }

            loading = true
            html = null
            error = null

            val result = runCatching {
                manager.chapter(sourceId, chapter.path)
            }

            result.onSuccess { content ->
                html = content

                historyStore.upsert(
                    NovelHistoryEntry(
                        sourceId = sourceId,
                        novelPath = novelPath,
                        novelTitle = novelName,
                        cover = novelCover,
                        chapterPath = chapter.path,
                        chapterName = chapter.name,
                        chapterNumber = null,
                        chapterIndex = currentIndex,
                        totalChapters = chapters.size,
                    ),
                )
            }

            result.onFailure {
                error = it.message ?: "تعذر تحميل الفصل"
            }

            loading = false
        }

        val hasPrevious = currentIndex > 0
        val hasNext = currentIndex < chapters.lastIndex

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = novelName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = currentChapter?.name
                                    ?: "الفصل ${currentIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.pop() },
                        ) {
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
                            .padding(
                                horizontal = 12.dp,
                                vertical = 8.dp,
                            ),
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
                            text = "${currentIndex + 1}/${chapters.size}",
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
        ) { paddingValues ->

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = error ?: "حدث خطأ",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                html != null -> {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.cacheMode =
                                    WebSettings.LOAD_DEFAULT
                            }
                        },
                        update = { webView ->
                            val background =
                                if (isSystemInDarkTheme()) {
                                    "#111111"
                                } else {
                                    "#ffffff"
                                }

                            val foreground =
                                if (isSystemInDarkTheme()) {
                                    "#eeeeee"
                                } else {
                                    "#171717"
                                }

                            val style = """
                                <style>
                                body {
                                    font-family: sans-serif;
                                    font-size: 19px;
                                    line-height: 1.95;
                                    padding: 20px;
                                    margin: 0;
                                    background: $background;
                                    color: $foreground;
                                }
                                img {
                                    max-width: 100%;
                                    height: auto;
                                }
                                p {
                                    margin-bottom: 1.1em;
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    )
                }
            }
        }
    }
}
'''

s = s[:start] + reader + "\n"

p.write_text(s)
PY

echo
echo "===== DIFF CHECK ====="
git diff --check

echo
echo "===== READER CHECK ====="
grep -n \
  "itemsIndexed\|NovelReaderScreen\|chapters =\|LaunchedEffect(currentIndex\|hasPrevious\|hasNext\|السابق\|التالي" \
  "$FILE" | tail -80

echo
echo "===== STATUS ====="
git status --short

echo
echo "======================================"
echo "✅ Reader repaired."
echo "======================================"
echo
echo "Now run:"
echo "./gradlew :app:compileDebugKotlin --no-daemon"
