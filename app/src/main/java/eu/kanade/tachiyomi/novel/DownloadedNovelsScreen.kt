package eu.kanade.tachiyomi.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.CommunityOfflineReaderScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadedNovelsScreen : Screen() {
    @Composable
    override fun Content() = DownloadedNovelsContent()
}

@Composable
fun DownloadedNovelsContent() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = remember { NovelOfflineStore(context) }
        var books by remember { mutableStateOf(emptyList<OfflineNovelBook>()) }
        var selected by remember { mutableStateOf<OfflineNovelBook?>(null) }
        fun reload() {
            scope.launch {
                books = withContext(Dispatchers.IO) {
                    store.migrateFromHistory(NovelHistoryStore(context).all())
                    store.downloadedNovels().filterNot { book ->
                        book.contentNotice.lowercase() in setOf("mature", "adult", "nsfw", "18+", "18plus") ||
                            hasAdultContentMarker(book.title, book.description)
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            reload()
            HomeScreen.downloadedRefreshEvents.collect {
                selected = null
                reload()
            }
        }

        Column(
            Modifier.fillMaxSize().background(MslDesignTokens.background).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                selected?.title ?: "الروايات المحملة",
                color = MslDesignTokens.textPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            selected?.let { book ->
                Text("${book.chapters.size} فصل محمّل", color = MslDesignTokens.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text("رجوع", Modifier.clickable { selected = null }.padding(12.dp), color = MslDesignTokens.accentBright)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            if (!book.cover.isNullOrBlank()) {
                                AsyncImage(book.cover, book.title, Modifier.size(width = 92.dp, height = 128.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                book.description?.takeIf(String::isNotBlank) ?: "لا يتوفر وصف محفوظ لهذه الرواية.",
                                Modifier.weight(1f), color = MslDesignTokens.textSecondary,
                            )
                        }
                        Text("الفصول المحملة فقط", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    items(book.chapters, key = { it.path }) { chapter ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val chapters = book.chapters.map { NovelChapter(name = it.title, path = it.path, chapterNumber = it.number) }
                                if (book.isCommunity) {
                                    navigator.push(CommunityOfflineReaderScreen(book, chapter))
                                } else {
                                    navigator.push(
                                        NovelReaderScreen(
                                            sourceId = book.sourceId,
                                            novelPath = book.novelPath,
                                            novelName = book.title,
                                            novelCover = book.cover,
                                            chapters = chapters,
                                            chapterIndex = book.chapters.indexOf(chapter),
                                            offlineOnly = true,
                                            novelDescription = book.description,
                                            sourceChapterIndices = book.chapters.map { it.order },
                                        ),
                                    )
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                            shape = MslDesignTokens.compactCardShape,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(chapter.title, Modifier.weight(1f), color = MslDesignTokens.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text("قراءة بلا إنترنت", color = MslDesignTokens.accentBright, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } ?: run {
                if (books.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد روايات محملة بعد", color = MslDesignTokens.textSecondary)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(books, key = { "${it.sourceId}:${it.novelPath}" }) { book ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selected = book },
                                shape = MslDesignTokens.cardShape,
                                colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MslDesignTokens.border),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!book.cover.isNullOrBlank()) AsyncImage(book.cover, book.title, Modifier.size(width = 76.dp, height = 104.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(width = 76.dp, height = 104.dp).clip(RoundedCornerShape(10.dp)).background(MslDesignTokens.surfaceRaised))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(book.title, color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(book.description?.takeIf(String::isNotBlank) ?: "وصف غير متوفر", color = MslDesignTokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                        Text("${book.chapters.size} فصل محمّل", color = MslDesignTokens.accentBright, fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { selected = book }, modifier = Modifier.size(48.dp).semantics { contentDescription = "فتح ${book.title} والفصول المحملة" }) {
                                        Text("›", color = MslDesignTokens.accentBright, fontSize = 28.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
