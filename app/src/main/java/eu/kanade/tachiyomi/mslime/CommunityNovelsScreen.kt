@file:Suppress("ktlint:standard:no-wildcard-imports")
package eu.kanade.tachiyomi.mslime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.novel.NovelOfflineStore
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

private val PdfBg = Color(0xFF030611)
private val PdfSurface = Color(0xFF11132F)
private val PdfSurface2 = Color(0xFF191A40)
private val PdfPurple = Color(0xFF8538F2)
private val PdfPurpleSoft = Color(0xFFBD75F7)
private val PdfMuted = Color(0xFF9290AB)
private val PdfLine = Color(0xFF292650)
private enum class CommunityPage { HOME, DETAILS, READER, EDIT_NOVEL, EDIT_CHAPTER }

object CommunityReaderChromeState {
    var readerVisible by mutableStateOf(false)
        private set

    fun updateReaderVisible(visible: Boolean) {
        readerVisible = visible
    }
}

class CommunityNovelOpenScreen(private val novelId: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var novel by remember { mutableStateOf<CommunityNovelApi.Novel?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var selectedChapter by remember { mutableStateOf<CommunityNovelApi.Chapter?>(null) }
    var reading by remember { mutableStateOf(false) }
        LaunchedEffect(novelId) {
            novel = withContext(Dispatchers.IO) { CommunityNovelApi.details(context, novelId) }
            loaded = true
        }
        val current = novel
        when {
            !loaded -> Box(Modifier.fillMaxSize().background(PdfBg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PdfPurpleSoft)
            }
            current == null -> Column(Modifier.fillMaxSize().background(PdfBg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("تعذّر فتح الرواية، أو أنها محجوبة لمحتوى +18.", color = Color.White)
                PdfButton("رجوع", { navigator.pop() }, modifier = Modifier.padding(top = 12.dp), compact = true)
            }
            reading -> CommunityReader(current, selectedChapter) { reading = false }
            else -> CommunityDetails(
                novel = current,
                onBack = { navigator.pop() },
                onRead = { chapter -> selectedChapter = chapter; reading = true },
                onEdit = {},
                onNewChapter = {},
                onRefresh = {},
            )
        }
    }
}

@Composable
fun CommunityNovelsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(CommunityPage.HOME) }
    var selected by remember { mutableStateOf<CommunityNovelApi.Novel?>(null) }
    var selectedChapter by remember { mutableStateOf<CommunityNovelApi.Chapter?>(null) }
    var novels by remember { mutableStateOf(emptyList<CommunityNovelApi.Novel>()) }
    var mine by remember { mutableStateOf(emptyList<CommunityNovelApi.Novel>()) }
    var filter by remember { mutableStateOf("latest") }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            novels = withContext(Dispatchers.IO) { CommunityNovelApi.list(context, filter, query, category) }
            mine = withContext(Dispatchers.IO) { CommunityNovelApi.mine(context) }
        }
    }
    LaunchedEffect(filter, query, category) { reload() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        when (page) {
            CommunityPage.HOME -> CommunityHome(novels, mine, filter, query, category, { query = it }, { filter = it }, { category = it }, { selected = it; page = CommunityPage.DETAILS }, { selected = null; page = CommunityPage.EDIT_NOVEL })
            CommunityPage.DETAILS -> selected?.let { novel ->
                CommunityDetails(
                    novel = novel,
                    onBack = { reload(); page = CommunityPage.HOME },
                    onRead = { chapter -> selectedChapter = chapter; page = CommunityPage.READER },
                    onEdit = { page = CommunityPage.EDIT_NOVEL },
                    onNewChapter = { selectedChapter = null; page = CommunityPage.EDIT_CHAPTER },
                    onRefresh = { reload() },
                    onEditChapter = { chapter -> selectedChapter = chapter; page = CommunityPage.EDIT_CHAPTER },
                )
            } ?: run { page = CommunityPage.HOME }
            CommunityPage.READER -> selected?.let { CommunityReader(it, selectedChapter, { page = CommunityPage.DETAILS }) } ?: run { page = CommunityPage.HOME }
            CommunityPage.EDIT_NOVEL -> CommunityNovelEditor(selected, { page = if (selected == null) CommunityPage.HOME else CommunityPage.DETAILS }) { id, addChapter -> scope.launch { selected = withContext(Dispatchers.IO) { CommunityNovelApi.details(context, id) }; reload(); page = if (addChapter) CommunityPage.EDIT_CHAPTER else CommunityPage.DETAILS } }
            CommunityPage.EDIT_CHAPTER -> selected?.let { CommunityChapterEditor(it, selectedChapter, { page = CommunityPage.DETAILS }) { reload(); page = CommunityPage.DETAILS } } ?: run { page = CommunityPage.HOME }
        }
    }
}

@Composable private fun PdfPage(title: String, back: (() -> Unit)? = null, onSearch: (() -> Unit)? = null, onNotifications: (() -> Unit)? = null, onLibrary: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(PdfBg).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { back() }.padding(horizontal = 6.dp))
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = if (back == null) TextAlign.End else TextAlign.Center)
            Text("⌕", color = Color.White, fontSize = 25.sp, modifier = Modifier.clickable { (onSearch ?: { Toast.makeText(context, "استخدم خانة البحث أسفل الصفحة", Toast.LENGTH_SHORT).show() })() }.padding(horizontal = 6.dp))
            Text("♧", color = PdfPurpleSoft, fontSize = 24.sp, modifier = Modifier.clickable { (onNotifications ?: { Toast.makeText(context, "لا توجد إشعارات جديدة", Toast.LENGTH_SHORT).show() })() }.padding(horizontal = 6.dp))
            Text("▱", color = PdfPurpleSoft, fontSize = 22.sp, modifier = Modifier.clickable { (onLibrary ?: { Toast.makeText(context, "المكتبة متاحة عبر زر الحفظ في تفاصيل الرواية", Toast.LENGTH_SHORT).show() })() }.padding(horizontal = 6.dp))
        }
        HorizontalDivider(color = PdfLine, thickness = 1.dp)
        Column(Modifier.fillMaxSize().padding(top = 10.dp), content = content)
    }
}

@Composable private fun PdfButton(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier, compact: Boolean = false, enabled: Boolean = true) {
    val colors = if (primary) ButtonDefaults.buttonColors(containerColor = PdfPurple, contentColor = Color.White) else ButtonDefaults.outlinedButtonColors(contentColor = PdfPurpleSoft)
    val border = if (primary) null else BorderStroke(1.dp, PdfPurpleSoft.copy(alpha = .7f))
    if (primary) Button(onClick, modifier.height(if (compact) 34.dp else 42.dp), enabled = enabled, shape = RoundedCornerShape(11.dp), colors = colors, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold) }
    else OutlinedButton(onClick, modifier.height(if (compact) 32.dp else 40.dp), enabled = enabled, shape = RoundedCornerShape(11.dp), border = border, colors = colors, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text, fontSize = if (compact) 11.sp else 13.sp) }
}

@Composable
private fun CommunityFavoriteButton(novel: CommunityNovelApi.Novel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by MslFavorites.snapshot.collectAsState()
    LaunchedEffect(favorites.loaded, favorites.loading) {
        if (!favorites.loaded && !favorites.loading) MslFavorites.load(context)
    }
    val isFavorite = favorites.items.any { it.kind == MslFavoriteKind.COMMUNITY && it.itemKey == novel.id }
    IconButton(
        onClick = {
            scope.launch {
                val failure = MslFavorites.toggle(
                    context,
                    MslFavoriteItem(
                        kind = MslFavoriteKind.COMMUNITY,
                        itemKey = novel.id,
                        title = novel.title,
                        cover = novel.coverPath,
                        sourceId = "community",
                    ),
                )
                if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
            }
        },
        modifier = modifier.size(48.dp).semantics {
            contentDescription = if (isFavorite) "إزالة ${novel.title} من المفضلة" else "إضافة ${novel.title} إلى المفضلة"
        },
    ) {
        Text(if (isFavorite) "♥" else "♡", color = if (isFavorite) Color(0xFFFBBF24) else PdfMuted, fontSize = 22.sp)
    }
}

@Composable private fun CommunityHome(novels: List<CommunityNovelApi.Novel>, mine: List<CommunityNovelApi.Novel>, filter: String, query: String, category: String?, onQuery: (String) -> Unit, onFilter: (String) -> Unit, onCategory: (String?) -> Unit, onOpen: (CommunityNovelApi.Novel) -> Unit, onCreate: () -> Unit) {
    var showCategories by remember { mutableStateOf(false) }
    PdfPage("روايات المجتمع") {
        PdfButton("＋  إنشاء رواية جديدة", onCreate, true, Modifier.fillMaxWidth())
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "الكل", "latest" to "الأحدث", "popular" to "الأكثر قراءة", "liked" to "الأكثر إعجابًا").forEach { (value, label) ->
                FilterChip(selected = (value == "all" && filter == "latest") || filter == value, onClick = { onFilter(if (value == "all") "latest" else value) }, label = { Text(label, fontSize = 10.sp, maxLines = 1) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PdfPurple, containerColor = PdfSurface, labelColor = Color.White, selectedLabelColor = Color.White))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text("روايات مميزة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.weight(1f)); Text("عرض الكل", color = PdfPurpleSoft, fontSize = 11.sp, modifier = Modifier.clickable { onFilter("latest") }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (novels.ifEmpty { mine }).take(3).forEach { FeaturedCard(it, onOpen) } }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("${if (category == null) "الأحدث" else categoryLabel(category)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.weight(1f)); Text("⌄ التصنيفات", color = PdfMuted, fontSize = 11.sp, modifier = Modifier.clickable { showCategories = true }) }
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth().height(48.dp), singleLine = true, placeholder = { Text("ابحث عن رواية", color = PdfMuted) }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PdfLine, focusedBorderColor = PdfPurple, unfocusedContainerColor = PdfSurface, focusedContainerColor = PdfSurface))
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) { items(novels, key = { it.id }) { NovelRow(it, onOpen) } }
        if (mine.isNotEmpty()) { Text("مسوداتي", color = PdfPurpleSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)); mine.filter { it.status != "published" }.forEach { NovelRow(it, onOpen) } }
        if (showCategories) AlertDialog(onDismissRequest = { showCategories = false }, confirmButton = { PdfButton("إغلاق", { showCategories = false }, compact = true) }, title = { Text("اختر التصنيف") }, text = { Column { Text("الكل", modifier = Modifier.fillMaxWidth().clickable { onCategory(null); showCategories = false }.padding(vertical = 10.dp)); listOf("fantasy" to "فانتازيا", "romance" to "رومانسية", "action" to "أكشن", "mystery" to "غموض", "horror" to "رعب", "adventure" to "مغامرة", "drama" to "دراما").forEach { (value, label) -> Text(label, modifier = Modifier.fillMaxWidth().clickable { onCategory(value); showCategories = false }.padding(vertical = 10.dp)) } } })
    }
}

@Composable private fun FeaturedCard(novel: CommunityNovelApi.Novel, onOpen: (CommunityNovelApi.Novel) -> Unit) {
    Column(Modifier.width(84.dp).clickable { onOpen(novel) }) {
        Box(Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(8.dp)).background(when (novel.category) { "fantasy" -> Color(0xFF3A3154); "romance" -> Color(0xFF2C594D); else -> Color(0xFF283A57) }), contentAlignment = Alignment.Center) {
            if (novel.coverPath != null) AsyncImage(novel.coverPath, novel.title, Modifier.fillMaxSize()) else Text("▣", color = Color.White.copy(alpha = .8f), fontSize = 25.sp)
            CommunityFavoriteButton(novel, Modifier.align(Alignment.TopEnd))
        }
        Text(novel.title.ifBlank { "عنوان الرواية" }, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        Text(novel.authorName.ifBlank { "قارئ السلايم" }, color = PdfMuted, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun NovelRow(novel: CommunityNovelApi.Novel, onOpen: (CommunityNovelApi.Novel) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(novel) }, colors = CardDefaults.cardColors(containerColor = PdfSurface), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp, 120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF51402B)), contentAlignment = Alignment.Center) { if (novel.coverPath != null) AsyncImage(novel.coverPath, novel.title, Modifier.fillMaxSize()) else Text("▣", color = Color.White.copy(alpha = .8f), fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(novel.title.ifBlank { "عنوان الرواية" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(novel.authorName.ifBlank { "قارئ السلايم" }, color = PdfMuted, fontSize = 10.sp)
                Text(novel.description.ifBlank { "وصف مختصر للرواية يظهر هنا في سطرين كحد أقصى." }, color = PdfMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PdfTag(categoryLabel(novel.category)); PdfTag(if (novel.completionStatus == "completed") "مكتملة" else "مستمرة") }
                Text("♡  ${novel.likeCount}    ◉  ${novel.viewCount}    ▫  ${novel.chapterCount} فصل", color = PdfMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
            CommunityFavoriteButton(novel)
        }
    }
}

@Composable private fun PdfTag(text: String) { Box(Modifier.background(PdfSurface2, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) { Text(text, color = PdfPurpleSoft, fontSize = 9.sp) } }

private suspend fun downloadCommunityNovelChapter(context: android.content.Context, novel: CommunityNovelApi.Novel, chapter: CommunityNovelApi.Chapter, order: Int): String? {
    val store = NovelOfflineStore(context)
    if (!MslWallet.enabled) return "تنزيل الفصول غير متاح حاليًا."
    if (store.contains("community", novel.id, chapter.id)) return null
    val key = MslChapterAccess.communityKey(novel.id, chapter.id) ?: return "هوية الفصل غير صالحة."
    val isOwner = novel.authorId == MslSupabase.uid(context)
    val unlock = withContext(Dispatchers.IO) { MslChapterAccess.unlock(context, listOf(key)) }
        ?: return "تعذّر التحقق من صلاحية الفصل. تحقق من الاتصال."
    if (!unlock.allowed) return "الرصيد غير كافٍ. المطلوب ${unlock.required} عملة."
    val content = if (isOwner && chapter.content.isNotBlank()) {
        chapter.content
    } else {
        withContext(Dispatchers.IO) { CommunityNovelApi.chapterContent(context, novel.id, chapter.id) }
            ?: return "تعذّر تحميل محتوى الفصل بعد التحقق من فتحه."
    }
    if (MslAdultContentFilter.isEnabled(context) && eu.kanade.tachiyomi.novel.hasAdultContentMarker(chapter.title, content)) {
        return "تم حجب هذا الفصل لأنه يتضمن محتوى غير مسموح."
    }
    val saved = runCatching {
        withContext(Dispatchers.IO) {
            store.save(
                sourceId = "community",
                novelPath = novel.id,
                chapterPath = chapter.id,
                html = content,
                title = novel.title,
                cover = novel.coverPath,
                description = novel.description,
                chapterTitle = "${chapter.number}. ${chapter.title}",
                chapterNumber = chapter.number.toDouble(),
                order = order,
                isCommunity = true,
                contentNotice = novel.contentNotice,
            )
        }
    }
    if (saved.isFailure) return saved.exceptionOrNull()?.message ?: "تعذّر حفظ الفصل على الجهاز."
    return null
}

@Composable private fun CommunityDetails(
    novel: CommunityNovelApi.Novel,
    onBack: () -> Unit,
    onRead: (CommunityNovelApi.Chapter) -> Unit,
    onEdit: () -> Unit,
    onNewChapter: () -> Unit,
    onRefresh: () -> Unit,
    onEditChapter: (CommunityNovelApi.Chapter) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember(novel.id) { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var viewCount by remember(novel.id) { mutableStateOf(novel.viewCount) }
    var liked by remember(novel.id) { mutableStateOf(novel.liked) }
    var likeCount by remember(novel.id) { mutableIntStateOf(novel.likeCount) }
    var likeBusy by remember(novel.id) { mutableStateOf(false) }
    var ratingSummary by remember(novel.id) { mutableStateOf(CommunityNovelApi.RatingSummary(novel.averageRating, novel.ratingCount, novel.myRating)) }
    var ratingBusy by remember(novel.id) { mutableStateOf(false) }
    var showReportDialog by remember(novel.id) { mutableStateOf(false) }
    var reportReason by remember(novel.id) { mutableStateOf("inappropriate") }
    var reportDescription by remember(novel.id) { mutableStateOf("") }
    var reportBusy by remember(novel.id) { mutableStateOf(false) }
    var novelComments by remember(novel.id) { mutableStateOf(emptyList<CommunityNovelApi.Comment>()) }
    var novelCommentsLoading by remember(novel.id) { mutableStateOf(false) }
    var novelCommentDraft by remember(novel.id) { mutableStateOf("") }
    var novelCommentSending by remember(novel.id) { mutableStateOf(false) }
    var showNovelComments by remember(novel.id) { mutableStateOf(false) }
    val isOwner = novel.authorId == MslSupabase.uid(context)
    var chapterAccessRevision by remember(novel.id) { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var downloadedIds by remember(novel.id) { mutableStateOf(emptySet<String>()) }
    fun loadNovelComments() {
        scope.launch {
            novelCommentsLoading = true
            novelComments = withContext(Dispatchers.IO) {
                CommunityNovelApi.comments(context, novel.id, null)
            }
            novelCommentsLoading = false
        }
    }
    LaunchedEffect(novel.id) {
        chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id, isOwner) }
        withContext(Dispatchers.IO) { MslChapterAccess.refresh(context) }
        chapterAccessRevision++
        val likes = withContext(Dispatchers.IO) { CommunityNovelApi.likeSummary(context, novel.id) }
        liked = likes.liked
        likeCount = likes.count
        loadNovelComments()
        ratingSummary = withContext(Dispatchers.IO) { CommunityNovelApi.ratingSummary(context, novel.id) }
        if (novel.status == "published" && withContext(Dispatchers.IO) { CommunityNovelApi.incrementView(context, novel.id) }) {
            viewCount += 1
        }
    }
    LaunchedEffect(chapters) {
        downloadedIds = withContext(Dispatchers.IO) {
            val store = NovelOfflineStore(context)
            chapters.filter { store.contains("community", novel.id, it.id) }.map { it.id }.toSet()
        }
    }
    PdfPage("عنوان الرواية", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(118.dp, 168.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF5A4028)), contentAlignment = Alignment.Center) { if (novel.coverPath != null) AsyncImage(novel.coverPath, novel.title, Modifier.fillMaxSize()) else Text("▣", color = Color.White, fontSize = 28.sp) }
            Column(Modifier.weight(1f)) { Text(novel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(novel.authorName.ifBlank { "قارئ السلايم" }, color = PdfMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PdfTag(categoryLabel(novel.category)); PdfTag(if (novel.completionStatus == "completed") "مكتملة" else "مستمرة") }; Spacer(Modifier.height(8.dp)); PdfButton(if (following) "✓ متابَع" else "＋ متابعة المؤلف", { following = !following; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleAuthorFollow(context, novel.authorId, following) } } }, compact = true) }
        }
        HorizontalDivider(color = PdfLine, modifier = Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PdfStat("فصل", "${chapters.count { it.status == "published" }}")
            PdfStat("قراءة", "$viewCount")
            PdfStat("إعجاب", "$likeCount")
            PdfStat("التقييم", if (ratingSummary.count == 0) "—" else "${ratingSummary.average}/10")
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PdfButton(if (liked) "♥ $likeCount" else "♡ $likeCount", {
                val target = !liked
                likeBusy = true
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { CommunityNovelApi.toggleLike(context, novel.id, target) }
                        if (saved) {
                            liked = target
                            likeCount = (likeCount + if (target) 1 else -1).coerceAtLeast(0)
                        } else Toast.makeText(context, "تعذّر حفظ الإعجاب. تحقق من الاتصال وتسجيل الدخول.", Toast.LENGTH_LONG).show()
                    } finally {
                        likeBusy = false
                    }
                }
            }, modifier = Modifier.defaultMinSize(minWidth = 56.dp), compact = true, enabled = !likeBusy)
            CommunityFavoriteButton(novel)
            PdfButton("التعليقات", { showNovelComments = true; loadNovelComments() }, compact = true)
            PdfButton("ابدأ القراءة", { chapters.firstOrNull { it.status == "published" }?.let(onRead) }, true, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("قيّم هذا العمل من 10 نجوم · ${ratingSummary.count} تقييم", color = PdfMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                (1..10).forEach { value ->
                    Text(
                        if (value <= ratingSummary.myRating) "★" else "☆",
                        color = if (value <= ratingSummary.myRating) Color(0xFFFFC857) else PdfMuted,
                        fontSize = 20.sp,
                        modifier = Modifier.clickable(enabled = !ratingBusy) {
                            scope.launch {
                                ratingBusy = true
                                val saved = withContext(Dispatchers.IO) { CommunityNovelApi.setRating(context, novel.id, value) }
                                if (saved) ratingSummary = withContext(Dispatchers.IO) { CommunityNovelApi.ratingSummary(context, novel.id) }
                                else Toast.makeText(context, "تعذّر حفظ تقييمك. تحقق من الاتصال وتسجيل الدخول.", Toast.LENGTH_LONG).show()
                                ratingBusy = false
                            }
                        }.semantics { contentDescription = "تقييم $value من 10 نجوم" },
                    )
                }
            }
            if (ratingSummary.myRating > 0) Text("تقييمك: ${ratingSummary.myRating}/10", color = PdfPurpleSoft, fontSize = 10.sp)
        }
        Text(novel.description.ifBlank { "وصف الرواية الكامل يظهر هنا." }, color = Color.White, fontSize = 12.sp, lineHeight = 21.sp, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Text(if (expanded) "أقل" else "المزيد", color = PdfPurpleSoft, fontSize = 11.sp, modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 5.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("تعليقات الرواية", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("${novelComments.size}", color = PdfPurpleSoft, fontSize = 11.sp)
        }
        when {
            novelCommentsLoading && novelComments.isEmpty() -> Text("جارٍ تحميل التعليقات…", color = PdfMuted, fontSize = 11.sp)
            novelComments.isEmpty() -> Text("لا توجد تعليقات بعد. اضغط «التعليقات» لإضافة أول تعليق.", color = PdfMuted, fontSize = 11.sp)
            else -> novelComments.takeLast(2).forEach { comment ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("قارئ ${comment.userId.take(8)}", color = PdfPurpleSoft, fontSize = 10.sp)
                    Text(comment.content, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("آخر تحديث: ${novel.updatedAt.take(10)}", color = PdfMuted, fontSize = 10.sp)
            Text("⚑ إبلاغ عن العمل", color = PdfPurpleSoft, fontSize = 10.sp, modifier = Modifier.clickable { showReportDialog = true })
        }
        if (novel.authorId == MslSupabase.uid(context)) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.End) { PdfButton("تعديل", onEdit, compact = true); Spacer(Modifier.width(6.dp)); PdfButton("＋ فصل", onNewChapter, compact = true); if (novel.status != "published") { Spacer(Modifier.width(6.dp)); PdfButton("نشر", { scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.setNovelStatus(context, novel.id, "published") }; onRefresh() } }, true, compact = true) } } }
        Text("الفصول المنشورة (${chapters.count { it.status == "published" }})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        val visibleChapters = chapters.filter { if (isOwner) it.status != "hidden" else it.status == "published" }
        LazyColumn { itemsIndexed(visibleChapters, key = { _, c -> c.id }) { index, chapter ->
            val accessKey = MslChapterAccess.communityKey(novel.id, chapter.id)
            val chapterUnlocked = remember(chapterAccessRevision, chapter.id) {
                isOwner || MslChapterAccess.isUnlocked(context, accessKey.orEmpty())
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${chapter.number}", color = PdfMuted, fontSize = 12.sp, modifier = Modifier.width(25.dp))
                Text(
                    chapter.title + if (chapter.status == "published") "" else " · مسودة",
                    color = if (chapter.status == "published") Color.White else PdfMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).clickable(enabled = chapter.status == "published") { onRead(chapter) }.padding(vertical = 10.dp),
                )
                if (isOwner) IconButton(onClick = { onEditChapter(chapter) }, modifier = Modifier.size(42.dp).semantics { contentDescription = "تعديل الفصل ${chapter.number}" }) { Text("✎", color = PdfPurpleSoft) }
                if (!chapterUnlocked) IconButton(
                    onClick = {
                        val key = accessKey ?: return@IconButton
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { MslChapterAccess.unlock(context, listOf(key)) }
                            if (result?.allowed == true) {
                                chapterAccessRevision++
                                Toast.makeText(context, "تم فتح الفصل مقابل ${result.charged} عملة", Toast.LENGTH_SHORT).show()
                            } else if (result?.status == "insufficient") {
                                Toast.makeText(context, "الرصيد غير كافٍ. المطلوب ${result.required} عملة.", Toast.LENGTH_LONG).show()
                            } else Toast.makeText(context, "تعذّر التحقق من المحفظة. تحقق من الاتصال.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.size(42.dp).semantics { contentDescription = "الفصل مقفل، فتح مقابل عملة واحدة" },
                ) { Icon(painterResource(R.drawable.ic_chapter_lock_24dp), contentDescription = "الفصل مقفل، فتح مقابل عملة واحدة", tint = Color(0xFFFF7070)) }
                IconButton(
                    onClick = {
                        if (chapter.id !in downloadedIds) scope.launch {
                            val failure = downloadCommunityNovelChapter(context, novel, chapter, index)
                            chapterAccessRevision++
                            if (failure == null) {
                                downloadedIds = downloadedIds + chapter.id
                                Toast.makeText(context, "تم تحميل الفصل إلى قائمة الروايات المحملة", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = chapter.status == "published" && (chapter.id in downloadedIds || MslWallet.enabled),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = if (chapter.id in downloadedIds) "${chapter.title} محمل" else "تحميل ${chapter.title}" },
                ) { Text(if (chapter.id in downloadedIds) "✓" else "⇩", color = PdfPurpleSoft, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider(color = PdfLine)
        } }
    }
    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { if (!reportBusy) showReportDialog = false },
            title = { Text("الإبلاغ عن الرواية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(
                        "inappropriate" to "محتوى غير مناسب",
                        "copyright" to "انتهاك حقوق النشر",
                        "abuse" to "إساءة أو تحرّش",
                        "spam" to "محتوى مزعج أو احتيالي",
                        "other" to "سبب آخر",
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { reportReason = value },
                        ) {
                            RadioButton(selected = reportReason == value, onClick = { reportReason = value })
                            Text(label, color = PdfMuted, fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(
                        value = reportDescription,
                        onValueChange = { reportDescription = it.take(1000) },
                        label = { Text("تفاصيل إضافية (اختياري)") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !reportBusy, onClick = {
                    scope.launch {
                        reportBusy = true
                        val result = withContext(Dispatchers.IO) {
                            CommunityNovelApi.reportNovel(context, novel.id, reportReason, reportDescription.trim())
                        }
                        result.fold(
                            {
                                showReportDialog = false
                                Toast.makeText(context, "تم إرسال البلاغ للمراجعة. شكرًا لمساعدتنا.", Toast.LENGTH_LONG).show()
                            },
                            { Toast.makeText(context, it.message ?: "تعذّر إرسال البلاغ", Toast.LENGTH_LONG).show() },
                        )
                        reportBusy = false
                    }
                }) { Text(if (reportBusy) "جارٍ الإرسال…" else "إرسال البلاغ") }
            },
            dismissButton = {
                TextButton(enabled = !reportBusy, onClick = { showReportDialog = false }) { Text("إلغاء") }
            },
        )
    }
    if (showNovelComments) {
        ModalBottomSheet(onDismissRequest = { showNovelComments = false }, containerColor = PdfSurface) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("تعليقات رواية ${novel.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                when {
                    novelCommentsLoading && novelComments.isEmpty() -> Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PdfPurpleSoft)
                    }
                    novelComments.isEmpty() -> Text("لا توجد تعليقات بعد.", color = PdfMuted)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(novelComments, key = { it.id }) { comment ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text("قارئ ${comment.userId.take(8)}", color = PdfPurpleSoft, fontSize = 11.sp)
                                Text(comment.content, color = Color.White, fontSize = 14.sp)
                                HorizontalDivider(color = PdfLine, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                if (MslSupabase.uid(context).isBlank()) {
                    Text("سجّل الدخول لمشاهدة التعليقات ونشرها.", color = PdfMuted, fontSize = 11.sp)
                } else {
                    OutlinedTextField(
                        value = novelCommentDraft,
                        onValueChange = { novelCommentDraft = it.take(1000) },
                        enabled = !novelCommentSending,
                        label = { Text("اكتب تعليقًا (1000 حرف كحد أقصى)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                    PdfButton(
                        if (novelCommentSending) "جارٍ الإرسال…" else "إرسال التعليق",
                        onClick = {
                            val body = novelCommentDraft.trim()
                            if (body.isNotEmpty() && !novelCommentSending) {
                                novelCommentSending = true
                                scope.launch {
                                    try {
                                        val saved = withContext(Dispatchers.IO) {
                                            CommunityNovelApi.addComment(context, novel.id, null, body)
                                        }
                                        if (saved) {
                                            novelCommentDraft = ""
                                            loadNovelComments()
                                        } else {
                                            Toast.makeText(context, "تعذّر نشر التعليق. تحقق من تسجيل الدخول والاتصال.", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        novelCommentSending = false
                                    }
                                }
                            }
                        },
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !novelCommentSending && novelCommentDraft.isNotBlank(),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable private fun PdfStat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Color.White, fontSize = 15.sp); Text(label, color = PdfMuted, fontSize = 10.sp) } }

@Composable private fun CommunityReader(novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        CommunityReaderChromeState.updateReaderVisible(true)
        onDispose { CommunityReaderChromeState.updateReaderVisible(false) }
    }
    val readerPrefs = remember { context.getSharedPreferences("manhwa_slime_reader_prefs", android.content.Context.MODE_PRIVATE) }
    var chapters by remember { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var index by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableFloatStateOf(readerPrefs.getFloat("font_size", 20f).coerceIn(14f, 32f)) }
    var lineSpacing by remember { mutableFloatStateOf(readerPrefs.getFloat("line_spacing", 1.8f).coerceIn(1.2f, 3f)) }
    var readingMargin by remember { mutableFloatStateOf(readerPrefs.getFloat("reading_margin", 20f).coerceIn(8f, 40f)) }
    var textAlignment by remember { mutableStateOf(readerPrefs.getString("text_alignment", "right") ?: "right") }
    var paper by remember { mutableStateOf(readerPrefs.getBoolean("community_paper", false)) }
    var amiri by remember { mutableStateOf(readerPrefs.getBoolean("community_amiri", true)) }
    var fullscreen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var indexSheet by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(novel.liked) }
    var chapterText by remember { mutableStateOf<String?>(null) }
    var chapterLocked by remember { mutableStateOf(false) }
    var chapterLoading by remember { mutableStateOf(true) }
    var chapterLoadError by remember { mutableStateOf<String?>(null) }
    var chapterAccessRevision by remember { mutableIntStateOf(0) }
    var commentsVisible by remember { mutableStateOf(false) }
    var chapterComments by remember { mutableStateOf(emptyList<CommunityNovelApi.Comment>()) }
    var commentDraft by remember { mutableStateOf("") }
    var commentSending by remember { mutableStateOf(false) }
    val isOwner = novel.authorId == MslSupabase.uid(context)
    DisposableEffect(fullscreen) {
        val window = context.findCommunityActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(novel.id, isOwner) { chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id, isOwner) }; index = chapters.indexOfFirst { it.id == initial?.id }.coerceAtLeast(0) }
    val chapter = chapters.getOrNull(index)
    LaunchedEffect(novel.id, chapter?.id, chapterAccessRevision) {
        chapterText = null
        chapterLocked = false
        chapterLoadError = null
        val current = chapter ?: run { chapterLoading = false; return@LaunchedEffect }
        chapterLoading = true
        val cached = withContext(Dispatchers.IO) {
            NovelOfflineStore(context).read("community", novel.id, current.id)
        }
        val hasCachedContent = !cached.isNullOrBlank()
        val key = MslChapterAccess.communityKey(novel.id, current.id)
        val allowed = isOwner || hasCachedContent || withContext(Dispatchers.IO) { MslChapterAccess.hasAccess(context, key) }
        if (!allowed) {
            chapterLocked = true
            chapterLoading = false
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.IO) {
            cached ?: if (isOwner && current.content.isNotBlank()) current.content
            else CommunityNovelApi.chapterContent(context, novel.id, current.id)
        }
        if (text.isNullOrBlank()) {
            chapterLoadError = "تعذّر تحميل محتوى الفصل. تحقق من الاتصال ثم أعد المحاولة."
        } else if (MslAdultContentFilter.isEnabled(context) && eu.kanade.tachiyomi.novel.hasAdultContentMarker(current.title, text)) {
            chapterLoadError = "تم حجب هذا الفصل بسبب تصنيفه."
        } else {
            chapterText = text
        }
        chapterLoading = false
    }
    val readerBg = if (paper) Color(0xFFF4ECD8) else PdfBg
    val readerText = if (paper) Color(0xFF26201A) else Color(0xFFE6E8EC)
    Box(Modifier.fillMaxSize().background(readerBg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (!fullscreen) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = if (paper) Color.DarkGray else Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 6.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(novel.title, color = if (paper) Color.DarkGray else PdfMuted, fontSize = 11.sp, maxLines = 1)
                        Text("الفصل ${chapter?.number ?: "٠٠"}", color = if (paper) Color.DarkGray else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("☷", color = PdfPurpleSoft, fontSize = 21.sp, modifier = Modifier.clickable { indexSheet = true }.padding(8.dp))
                    Text("Aa", color = PdfPurpleSoft, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { settings = true }.padding(8.dp))
                    Text("⛶", color = PdfPurpleSoft, fontSize = 20.sp, modifier = Modifier.clickable { fullscreen = true }.padding(8.dp))
                }
                HorizontalDivider(color = if (paper) Color(0xFFCCC2AE) else PdfLine)
            }
            if (chapter == null || chapterLoading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PdfPurpleSoft) }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { fullscreen = !fullscreen })
                    },
                    contentPadding = PaddingValues(vertical = 24.dp),
                ) {
                    item {
                        if (chapterLocked) {
                            Column(
                                Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text("هذا الفصل مقفل", color = if (paper) Color.DarkGray else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("فتح الفصل يكلف عملة واحدة. لن يُحمّل النص قبل تأكيد الاستحقاق.", color = readerText)
                                PdfButton("فتح مقابل عملة واحدة", {
                                    val key = MslChapterAccess.communityKey(novel.id, chapter.id)
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            MslChapterAccess.unlock(context, listOfNotNull(key))
                                        }
                                        if (result?.allowed == true) chapterAccessRevision++
                                        else if (result?.status == "insufficient") {
                                            Toast.makeText(context, "الرصيد غير كافٍ. المطلوب ${result.required} عملة.", Toast.LENGTH_LONG).show()
                                        } else Toast.makeText(context, "تعذّر التحقق من المحفظة. تحقق من الاتصال.", Toast.LENGTH_LONG).show()
                                    }
                                }, true, compact = true)
                            }
                        } else if (chapterLoadError != null) {
                            Text(chapterLoadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                        } else {
                            Text(
                                chapter.title,
                                color = if (paper) Color.DarkGray else Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = if (amiri) MslUiFont else null,
                                modifier = Modifier.padding(horizontal = readingMargin.dp),
                            )
                            Text(
                                novel.authorName.ifBlank { "قارئ السلايم" },
                                color = if (paper) Color.Gray else PdfMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = readingMargin.dp).padding(top = 6.dp),
                            )
                            Text(
                                chapterText.orEmpty(),
                                color = readerText,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineSpacing).sp,
                                fontFamily = if (amiri) MslUiFont else null,
                                textAlign = when (textAlignment) {
                                    "center" -> TextAlign.Center
                                    "justify" -> TextAlign.Justify
                                    else -> TextAlign.Right
                                },
                                modifier = Modifier.padding(horizontal = readingMargin.dp).padding(top = 24.dp),
                            )
                        }
                    }
                }
                if (!fullscreen) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfButton("‹ السابق", { if (index > 0) { index--; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } } }, modifier = Modifier.weight(1f), compact = true)
                        PdfButton(if (liked) "♥" else "♡", {
                            val target = !liked
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { CommunityNovelApi.toggleLike(context, novel.id, target) }
                                if (saved) liked = target
                                else Toast.makeText(context, "تعذّر حفظ الإعجاب. تحقق من الاتصال وتسجيل الدخول.", Toast.LENGTH_LONG).show()
                            }
                        }, compact = true)
                        CommunityFavoriteButton(novel)
                        if (!chapterLocked) PdfButton(
                            "تعليقات",
                            {
                                commentsVisible = true
                                val chapterId = chapter.id
                                scope.launch {
                                    chapterComments = withContext(Dispatchers.IO) {
                                        CommunityNovelApi.comments(context, novel.id, chapterId)
                                    }
                                }
                            },
                            compact = true,
                        )
                        PdfButton("التالي ›", { if (index < chapters.lastIndex) { index++; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } } }, primary = true, modifier = Modifier.weight(1f), compact = true)
                    }
                }
            }
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = PdfSurface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("إعدادات القراءة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("حجم الخط  ${fontSize.toInt()}", color = PdfMuted); Slider(value = fontSize, onValueChange = { fontSize = it; readerPrefs.edit().putFloat("font_size", it).apply() }, valueRange = 14f..30f, colors = SliderDefaults.colors(thumbColor = PdfPurpleSoft, activeTrackColor = PdfPurpleSoft))
                Text("تباعد الأسطر", color = PdfMuted); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1.5f to "ضيق", 1.8f to "متوسط", 2.1f to "واسع").forEach { (v, label) -> PdfButton(label, { lineSpacing = v; readerPrefs.edit().putFloat("line_spacing", v).apply() }, lineSpacing == v, modifier = Modifier.weight(1f), compact = true) } }
                Text("الهامش", color = PdfMuted); Slider(value = readingMargin, onValueChange = { readingMargin = it; readerPrefs.edit().putFloat("reading_margin", it).apply() }, valueRange = 8f..40f, colors = SliderDefaults.colors(thumbColor = PdfPurpleSoft, activeTrackColor = PdfPurpleSoft))
                Text("محاذاة النص", color = PdfMuted); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("right" to "يمين", "center" to "وسط", "justify" to "ضبط").forEach { (value, label) -> PdfButton(label, { textAlignment = value; readerPrefs.edit().putString("text_alignment", value).apply() }, textAlignment == value, modifier = Modifier.weight(1f), compact = true) } }
                Text("الخلفية", color = PdfMuted); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { PdfButton("داكن", { paper = false; readerPrefs.edit().putBoolean("community_paper", false).apply() }, !paper, compact = true); PdfButton("ورقي", { paper = true; readerPrefs.edit().putBoolean("community_paper", true).apply() }, paper, compact = true); PdfButton("فاتح", { paper = false; readerPrefs.edit().putBoolean("community_paper", false).apply() }, compact = true) }
                Text("الخط", color = PdfMuted); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfButton("تاجوال", { amiri = true; readerPrefs.edit().putBoolean("community_amiri", true).apply() }, amiri, modifier = Modifier.weight(1f), compact = true); PdfButton("افتراضي", { amiri = false; readerPrefs.edit().putBoolean("community_amiri", false).apply() }, !amiri, modifier = Modifier.weight(1f), compact = true) }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (indexSheet) ModalBottomSheet(onDismissRequest = { indexSheet = false }, containerColor = PdfSurface) { Column(Modifier.padding(20.dp)) { Text("فهرس الفصول", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); chapters.forEachIndexed { i, ch -> Text("${ch.number}. ${ch.title}", color = if (i == index) PdfPurpleSoft else Color.White, modifier = Modifier.fillMaxWidth().clickable { index = i; indexSheet = false }.padding(vertical = 12.dp)) } } }
        if (commentsVisible && chapter != null) ModalBottomSheet(onDismissRequest = { commentsVisible = false }, containerColor = PdfSurface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تعليقات الفصل ${chapter.number}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (chapterComments.isEmpty()) Text("لا توجد تعليقات بعد.", color = PdfMuted)
                else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(chapterComments, key = { it.id }) { comment ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("قارئ ${comment.userId.take(8)}", color = PdfPurpleSoft, fontSize = 11.sp)
                            Text(comment.content, color = Color.White, fontSize = 14.sp)
                            HorizontalDivider(color = PdfLine, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = commentDraft,
                    onValueChange = { commentDraft = it.take(1000) },
                    enabled = !chapterLocked && !commentSending,
                    label = { Text("اكتب تعليقًا (1000 حرف كحد أقصى)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Button(
                    enabled = !chapterLocked && !commentSending && commentDraft.isNotBlank(),
                    onClick = {
                        val chapterId = chapter.id
                        commentSending = true
                        scope.launch {
                            try {
                                val saved = withContext(Dispatchers.IO) {
                                    CommunityNovelApi.addComment(context, novel.id, chapterId, commentDraft)
                                }
                                if (saved) {
                                    commentDraft = ""
                                    chapterComments = withContext(Dispatchers.IO) {
                                        CommunityNovelApi.comments(context, novel.id, chapterId)
                                    }
                                } else Toast.makeText(context, "تعذّر نشر التعليق. تحقق من فتح الفصل والاتصال.", Toast.LENGTH_LONG).show()
                            } finally {
                                commentSending = false
                            }
                        }
                    },
                ) { Text(if (commentSending) "جارٍ الإرسال…" else "إرسال التعليق") }
            }
        }
    }
}

@Composable private fun CommunityNovelEditor(initial: CommunityNovelApi.Novel?, onBack: () -> Unit, onSaved: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var authorName by remember(initial?.id) { mutableStateOf(initial?.authorName.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var categories by remember { mutableStateOf(initial?.tags?.ifEmpty { setOf(initial.category) } ?: setOf("fantasy")) }
    var completion by remember { mutableStateOf(initial?.completionStatus ?: "ongoing") }
    var cover by remember { mutableStateOf(initial?.coverPath) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initial?.id) {
        if (authorName.isBlank()) {
            authorName = withContext(Dispatchers.IO) { MslCloudSync.profile(context)?.name.orEmpty() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) { encodeCover(context, uri) }
            if (cover == null) message = "تعذر قراءة الصورة أو حجمها غير مناسب"
        }
    }
    fun save(andChapter: Boolean) {
        if (title.isBlank()) { message = "اكتب عنوان الرواية أولًا"; return }
        if (authorName.isBlank()) { message = "اكتب اسم الكاتب أولًا"; return }
        if (categories.isEmpty()) { message = "اختر تصنيفًا واحدًا على الأقل"; return }
        scope.launch {
            val r = withContext(Dispatchers.IO) { CommunityNovelApi.saveNovel(context, initial, title.trim(), authorName.trim(), description.trim(), categories.first(), "ar", completion, categories, cover) }
            r.fold({ onSaved(it, andChapter) }) { message = it.message ?: "تعذر الحفظ" }
        }
    }
    PdfPage(if (initial == null) "إنشاء رواية" else "تعديل الرواية", onBack) {
        Text(if (initial == null) "مسودة · لم يتم الحفظ" else "مسودة · آخر تعديل محفوظ", color = PdfMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(96.dp, 136.dp).clip(RoundedCornerShape(10.dp)).background(PdfSurface2), contentAlignment = Alignment.Center) {
                if (cover?.startsWith("data:image") == true) Text("▣", color = PdfPurpleSoft, fontSize = 25.sp) else Text("معاينة الغلاف", color = PdfMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { PdfButton("اختيار غلاف", { picker.launch("image/*") }, compact = false); Text("JPG أو PNG أو WebP · حد أقصى 1MB", color = PdfMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        PdfInput(title, { title = it.take(120) }, "عنوان الرواية *", "اكتب عنوانًا للرواية", 64)
        PdfInput(authorName, { authorName = it.take(80) }, "اسم الكاتب *", "الاسم الذي سيظهر للقراء", 56)
        PdfInput(description, { description = it.take(5000) }, "الوصف *", "ملخص الرواية الذي سيظهر للقراء", 128)
        Text("التصنيفات * · يمكنك اختيار أكثر من تصنيف", color = PdfMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("fantasy" to "فانتازيا", "romance" to "رومانسية", "action" to "أكشن", "mystery" to "غموض", "horror" to "رعب", "adventure" to "مغامرة").forEach { (v, l) ->
                FilterChip(selected = v in categories, onClick = { categories = if (v in categories) categories - v else categories + v }, label = { Text(l, fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PdfPurple, containerColor = PdfSurface, labelColor = Color.White, selectedLabelColor = Color.White))
            }
        }
        Text("حالة الرواية", color = PdfMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PdfButton("مستمرة", { completion = "ongoing" }, completion == "ongoing", compact = true); PdfButton("مكتملة", { completion = "completed" }, completion == "completed", compact = true) }
        message?.let { Text(it, color = Color(0xFFFFC857), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.weight(1f)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) { PdfButton("حفظ كمسودة", { save(false) }, modifier = Modifier.weight(1f)); PdfButton("حفظ وإضافة فصل", { save(true) }, true, modifier = Modifier.weight(1f)) }
    }
}

@Composable private fun PdfInput(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, height: Int = 52) { OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().heightIn(min = height.dp).padding(top = 6.dp), minLines = if (height >= 100) 4 else 1, maxLines = if (height >= 100) 8 else 1, label = if (label.isBlank()) null else ({ Text(label, fontSize = 10.sp) }), placeholder = { Text(placeholder, color = PdfMuted, fontSize = 11.sp) }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PdfLine, focusedBorderColor = PdfPurple, unfocusedContainerColor = PdfSurface, focusedContainerColor = PdfSurface)) }

@Composable private fun CommunityChapterEditor(novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember(novel.id, initial?.id) { mutableStateOf((initial?.number ?: (novel.chapterCount + 1).coerceAtLeast(1)).toString()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var previousContent by remember { mutableStateOf<String?>(null) }
    var redoContent by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember(novel.id, initial?.id) { mutableStateOf(false) }
    fun saveChapterNow(publish: Boolean) {
        if (saving) return
        if (title.isBlank() || content.isBlank()) {
            message = "العنوان والمحتوى مطلوبان"
            return
        }
        scope.launch {
            saving = true
            message = null
            try {
                val result = withContext(Dispatchers.IO) {
                    CommunityNovelApi.saveChapter(context, novel.id, initial, number.toIntOrNull() ?: 1, title, content, publish)
                }
                result.fold({ onSaved() }) { message = it.message ?: "تعذّر الحفظ" }
            } catch (error: Exception) {
                message = error.message ?: "تعذّر الاتصال أثناء حفظ الفصل"
            } finally {
                saving = false
            }
        }
    }
    LaunchedEffect(novel.id, initial?.id) {
        if (initial == null) {
            number = withContext(Dispatchers.IO) { CommunityNovelApi.nextChapterNumber(context, novel.id) }.toString()
        }
    }
    PdfPage("عنوان الرواية", onBack) {
        Text("الفصل ${number.ifBlank { "٠٠" }}", color = PdfMuted, fontSize = 11.sp)
        PdfInput(number, { number = it.filter(Char::isDigit) }, "رقم الفصل", "٠٠", 50)
        PdfInput(title, { title = it }, "عنوان الفصل", "عنوان الفصل", 58)
        OutlinedTextField(content, { previousContent = content; redoContent = null; content = it }, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), placeholder = { Text("ابدأ كتابة فصلك هنا...", color = PdfMuted) }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 17.sp, lineHeight = 30.sp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = PdfPurple, unfocusedContainerColor = PdfBg, focusedContainerColor = PdfBg))
        message?.let { Text(it, color = Color(0xFFFFC857), fontSize = 11.sp) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PdfButton("تراجع", { previousContent?.let { redoContent = content; content = it; previousContent = null } }, compact = true, enabled = !saving)
            PdfButton("إعادة", { redoContent?.let { previousContent = content; content = it; redoContent = null } }, compact = true, enabled = !saving)
            Spacer(Modifier.weight(1f))
            PdfButton(if (saving) "جارٍ الحفظ…" else "حفظ كمسودة", { saveChapterNow(false) }, compact = true, enabled = !saving)
            PdfButton("معاينة", { preview = true }, compact = true, enabled = !saving)
            PdfButton(if (saving) "جارٍ النشر…" else "نشر", { saveChapterNow(true) }, true, compact = true, enabled = !saving)
        }
        if (preview) AlertDialog(onDismissRequest = { preview = false }, confirmButton = { PdfButton("إغلاق", { preview = false }, compact = true) }, title = { Text(title.ifBlank { "معاينة الفصل" }) }, text = { Text(content.ifBlank { "لا يوجد محتوى بعد" }, lineHeight = 25.sp) })
    }
}

private fun saveChapter(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, number: String, title: String, content: String, publish: Boolean, onError: (String) -> Unit, onSaved: () -> Unit) { if (title.isBlank() || content.isBlank()) { onError("العنوان والمحتوى مطلوبان"); return }; scope.launch { val result = withContext(Dispatchers.IO) { CommunityNovelApi.saveChapter(context, novel.id, initial, number.toIntOrNull() ?: 1, title, content, publish) }; result.fold({ onSaved() }) { onError(it.message ?: "تعذر الحفظ") } } }
private fun encodeCover(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val ratio = minOf(640f / source.width.coerceAtLeast(1), 640f / source.height.coerceAtLeast(1), 1f)
    val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true) else source
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
    val bytes = output.toByteArray()
    if (bytes.size > 1024 * 1024) return null
    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()
private fun categoryLabel(value: String): String = mapOf("fantasy" to "فانتازيا", "romance" to "رومانسية", "action" to "أكشن", "adventure" to "مغامرة", "mystery" to "غموض", "horror" to "رعب", "drama" to "دراما", "sci_fi" to "خيال علمي", "comedy" to "كوميديا", "historical" to "تاريخي")[value] ?: "أخرى"

fun android.content.Context.findCommunityActivity(): android.app.Activity? {
    var current: android.content.Context = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
