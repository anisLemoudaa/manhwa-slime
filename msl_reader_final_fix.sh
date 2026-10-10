#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt"

echo "🛠️ Fixing NovelReaderScreen safely..."

cp "$FILE" "/tmp/NovelSectionScreen.before_final_fix.kt"

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/eu/kanade/tachiyomi/novel/NovelSectionScreen.kt")
s = p.read_text()

# ---------------------------------------------------------
# 1. Import itemsIndexed
# ---------------------------------------------------------

if "import androidx.compose.foundation.lazy.itemsIndexed" not in s:
    s = s.replace(
        "import androidx.compose.foundation.lazy.items\n",
        "import androidx.compose.foundation.lazy.items\n"
        "import androidx.compose.foundation.lazy.itemsIndexed\n",
    )

# ---------------------------------------------------------
# 2. Details screen: make chapter list indexed
# ---------------------------------------------------------

s = s.replace(
    "items(d.chapters.orEmpty()) { chapter ->",
    "itemsIndexed(d.chapters.orEmpty()) { index, chapter ->",
)

# ---------------------------------------------------------
# 3. Replace every old 3-argument reader call
# ---------------------------------------------------------

old_call = "navigator.push(NovelReaderScreen(sourceId, chapter.path, chapter.name))"

new_call = """navigator.push(
                                    NovelReaderScreen(
                                        sourceId = sourceId,
                                        novelPath = d.path,
                                        novelName = d.name ?: fallbackName,
                                        novelCover = d.cover,
                                        chapters = d.chapters.orEmpty(),
                                        chapterIndex = index,
                                    ),
                                )"""

s = s.replace(old_call, new_call)

# ---------------------------------------------------------
# 4. Replace any older named-argument reader call
# ---------------------------------------------------------

pattern = re.compile(
    r"""NovelReaderScreen\(
\s*sourceId\s*=\s*sourceId,
\s*novelPath\s*=\s*d\.path,
\s*novelName\s*=\s*d\.name\s*\?:\s*fallbackName,
\s*novelCover\s*=\s*d\.cover,
\s*chapterPath\s*=\s*chapter\.path,
\s*chapterName\s*=\s*chapter\.name,
\s*chapterIndex\s*=\s*index,
\s*totalChapters\s*=\s*d\.chapters\?\.size\s*\?:\s*0,
\s*\)""",
    re.MULTILINE,
)

s = pattern.sub(new_call.strip(), s)

# ---------------------------------------------------------
# 5. Find the reader class
# ---------------------------------------------------------

start = s.find("class NovelReaderScreen(")

if start == -1:
    raise SystemExit("❌ NovelReaderScreen not found")

# ---------------------------------------------------------
# 6. Safe reader implementation
# ---------------------------------------------------------

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
        val dark = isSystemInDarkTheme()

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
        var loading by remember { mutableStateOf(true) }

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
                        chapterNumber = chapter.chapterNumber,
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
                                text = chapters
                                    .getOrNull(currentIndex)
                                    ?.name
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
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = hasPrevious && !loading,
                            onClick = {
                                if (hasPrevious) {
                                    currentIndex--
                                }
                            },
                        ) {
                            Text("‹ السابق")
                        }

                        Text(
                            text = "${currentIndex + 1}/${chapters.size}",
                            fontWeight = FontWeight.Bold,
                        )

                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = hasNext && !loading,
                            onClick = {
                                if (hasNext) {
                                    currentIndex++
                                }
                            },
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        CircularProgressIndicator()
                    }
                }

                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
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
                            .padding(padding),
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
                            val bg =
                                if (dark) "#111111" else "#ffffff"

                            val fg =
                                if (dark) "#eeeeee" else "#171717"

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

                                p {
                                    margin-bottom: 1.1em;
                                }

                                a {
                                    color: inherit;
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
                            .padding(padding)
                            .wrapContentSize(Alignment.Center),
                    ) {
                        Text("لا يوجد محتوى")
                    }
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
echo "===== CALL CHECK ====="
grep -n "NovelReaderScreen(" "$FILE"

echo
echo "===== CHAPTER CHECK ====="
grep -n "itemsIndexed\|chapters = d.chapters\|chapterIndex = index" "$FILE"

echo
echo "===== STATUS ====="
git status --short

echo
echo "======================================"
echo "✅ Reader fixed safely."
echo "======================================"
echo
echo "Run:"
echo "./gradlew :app:compileDebugKotlin --no-daemon"
