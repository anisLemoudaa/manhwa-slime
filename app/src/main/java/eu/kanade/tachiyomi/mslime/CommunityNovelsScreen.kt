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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val PdfBg = Color(0xFF030611)
private val PdfSurface = Color(0xFF11132F)
private val PdfSurface2 = Color(0xFF191A40)
private val PdfPurple = Color(0xFF8538F2)
private val PdfPurpleSoft = Color(0xFFBD75F7)
private val PdfMuted = Color(0xFF9290AB)
private val PdfLine = Color(0xFF292650)
private enum class CommunityPage { HOME, DETAILS, READER, EDIT_NOVEL, EDIT_CHAPTER }

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
                CommunityDetails(novel, { page = CommunityPage.HOME }, { ch -> selectedChapter = ch; page = CommunityPage.READER }, { page = CommunityPage.EDIT_NOVEL }, { selectedChapter = null; page = CommunityPage.EDIT_CHAPTER }, { reload() })
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

@Composable private fun PdfButton(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = if (primary) ButtonDefaults.buttonColors(containerColor = PdfPurple, contentColor = Color.White) else ButtonDefaults.outlinedButtonColors(contentColor = PdfPurpleSoft)
    val border = if (primary) null else BorderStroke(1.dp, PdfPurpleSoft.copy(alpha = .7f))
    if (primary) Button(onClick, modifier.height(if (compact) 34.dp else 42.dp), shape = RoundedCornerShape(11.dp), colors = colors, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold) }
    else OutlinedButton(onClick, modifier.height(if (compact) 32.dp else 40.dp), shape = RoundedCornerShape(11.dp), border = border, colors = colors, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text, fontSize = if (compact) 11.sp else 13.sp) }
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
        Text("اسم المؤلف", color = PdfMuted, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun NovelRow(novel: CommunityNovelApi.Novel, onOpen: (CommunityNovelApi.Novel) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(novel) }, colors = CardDefaults.cardColors(containerColor = PdfSurface), shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp, 120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF51402B)), contentAlignment = Alignment.Center) { if (novel.coverPath != null) AsyncImage(novel.coverPath, novel.title, Modifier.fillMaxSize()) else Text("▣", color = Color.White.copy(alpha = .8f), fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(novel.title.ifBlank { "عنوان الرواية" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("اسم المؤلف", color = PdfMuted, fontSize = 10.sp)
                Text(novel.description.ifBlank { "وصف مختصر للرواية يظهر هنا في سطرين كحد أقصى." }, color = PdfMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PdfTag(categoryLabel(novel.category)); PdfTag(if (novel.completionStatus == "completed") "مكتملة" else "مستمرة") }
                Text("♡  ${novel.likeCount}    ◉  ${novel.viewCount}    ▫  ${novel.chapterCount} فصل", color = PdfMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
            CommunityFavoriteButton(novel)
        }
    }
}

@Composable private fun PdfTag(text: String) { Box(Modifier.background(PdfSurface2, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) { Text(text, color = PdfPurpleSoft, fontSize = 9.sp) } }

@Composable private fun CommunityDetails(novel: CommunityNovelApi.Novel, onBack: () -> Unit, onRead: (CommunityNovelApi.Chapter) -> Unit, onEdit: () -> Unit, onNewChapter: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember(novel.id) { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var liked by remember { mutableStateOf(novel.liked) }
    var following by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(novel.id) { chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id, novel.authorId == MslSupabase.uid(context)) } }
    PdfPage("عنوان الرواية", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(118.dp, 168.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF5A4028)), contentAlignment = Alignment.Center) { if (novel.coverPath != null) AsyncImage(novel.coverPath, novel.title, Modifier.fillMaxSize()) else Text("▣", color = Color.White, fontSize = 28.sp) }
            Column(Modifier.weight(1f)) { Text(novel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("اسم المؤلف", color = PdfMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PdfTag(categoryLabel(novel.category)); PdfTag(if (novel.completionStatus == "completed") "مكتملة" else "مستمرة") }; Spacer(Modifier.height(8.dp)); PdfButton(if (following) "✓ متابَع" else "＋ متابعة المؤلف", { following = !following; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleAuthorFollow(context, novel.authorId, following) } } }, compact = true) }
        }
        HorizontalDivider(color = PdfLine, modifier = Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { PdfStat("فصل", "${chapters.size}"); PdfStat("قراءة", "${novel.viewCount}"); PdfStat("إعجاب", "${novel.likeCount}"); PdfStat("تقييم", "—") }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfButton("♡", { liked = !liked; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleLike(context, novel.id, liked) } } }, modifier = Modifier.width(40.dp), compact = true); CommunityFavoriteButton(novel); PdfButton("ابدأ القراءة", { chapters.firstOrNull()?.let(onRead) }, true, Modifier.weight(1f)) }
        Text(novel.description.ifBlank { "وصف الرواية الكامل يظهر هنا." }, color = Color.White, fontSize = 12.sp, lineHeight = 21.sp, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Text(if (expanded) "أقل" else "المزيد", color = PdfPurpleSoft, fontSize = 11.sp, modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("آخر تحديث: ${novel.updatedAt.take(10)}", color = PdfMuted, fontSize = 10.sp); Text("⚑ إبلاغ", color = PdfPurpleSoft, fontSize = 10.sp, modifier = Modifier.clickable { scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.reportNovel(context, novel.id) } } }) }
        if (novel.authorId == MslSupabase.uid(context)) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.End) { PdfButton("تعديل", onEdit, compact = true); Spacer(Modifier.width(6.dp)); PdfButton("＋ فصل", onNewChapter, compact = true); if (novel.status != "published") { Spacer(Modifier.width(6.dp)); PdfButton("نشر", { scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.setNovelStatus(context, novel.id, "published") }; onRefresh() } }, true, compact = true) } } }
        Text("الفصول المنشورة (${chapters.count { it.status == "published" }})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn { itemsIndexed(chapters.filter { it.status == "published" }, key = { _, c -> c.id }) { _, chapter -> Row(Modifier.fillMaxWidth().clickable { onRead(chapter) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Text("${chapter.number}", color = PdfMuted, fontSize = 12.sp, modifier = Modifier.width(25.dp)); Text(chapter.title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("٠٠/٠٠", color = PdfMuted, fontSize = 9.sp) }; HorizontalDivider(color = PdfLine) } }
    }
}

@Composable private fun PdfStat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Color.White, fontSize = 15.sp); Text(label, color = PdfMuted, fontSize = 10.sp) } }

@Composable private fun CommunityReader(novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var index by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableFloatStateOf(20f) }
    var lineSpacing by remember { mutableFloatStateOf(1.8f) }
    var paper by remember { mutableStateOf(false) }
    var amiri by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var indexSheet by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(novel.liked) }
    LaunchedEffect(novel.id) { chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id) }; index = chapters.indexOfFirst { it.id == initial?.id }.coerceAtLeast(0) }
    val chapter = chapters.getOrNull(index)
    val readerBg = if (paper) Color(0xFFF4ECD8) else PdfBg
    val readerText = if (paper) Color(0xFF26201A) else Color(0xFFE6E8EC)
    Box(Modifier.fillMaxSize().background(readerBg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = if (paper) Color.DarkGray else Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(novel.title, color = if (paper) Color.DarkGray else PdfMuted, fontSize = 11.sp, maxLines = 1); Text("الفصل ${chapter?.number ?: "٠٠"}", color = if (paper) Color.DarkGray else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                Text("☷", color = PdfPurpleSoft, fontSize = 21.sp, modifier = Modifier.clickable { indexSheet = true }.padding(8.dp))
                Text("Aa", color = PdfPurpleSoft, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { settings = true }.padding(8.dp))
            }
            HorizontalDivider(color = if (paper) Color(0xFFCCC2AE) else PdfLine)
            if (chapter == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PdfPurpleSoft) } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 24.dp)) { item { Text(chapter.title, color = if (paper) Color.DarkGray else Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = if (amiri) MslUiFont else null); Text("اسم المؤلف", color = if (paper) Color.Gray else PdfMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)); Text(chapter.content, color = readerText, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp, fontFamily = if (amiri) MslUiFont else null, modifier = Modifier.padding(top = 24.dp)) } }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfButton("‹ السابق", { if (index > 0) { index--; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } } }, modifier = Modifier.weight(1f), compact = true); PdfButton(if (liked) "♥" else "♡", { liked = !liked; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleLike(context, novel.id, liked) } } }, compact = true); CommunityFavoriteButton(novel); PdfButton("التالي ›", { if (index < chapters.lastIndex) { index++; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } } }, primary = true, modifier = Modifier.weight(1f), compact = true) }
            }
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = PdfSurface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("إعدادات القراءة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("حجم الخط  ${fontSize.toInt()}", color = PdfMuted); Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 14f..30f, colors = SliderDefaults.colors(thumbColor = PdfPurpleSoft, activeTrackColor = PdfPurpleSoft))
                Text("تباعد الأسطر", color = PdfMuted); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1.5f to "ضيق", 1.8f to "متوسط", 2.1f to "واسع").forEach { (v, label) -> PdfButton(label, { lineSpacing = v }, lineSpacing == v, modifier = Modifier.weight(1f), compact = true) } }
                Text("الخلفية", color = PdfMuted); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { PdfButton("داكن", { paper = false }, !paper, compact = true); PdfButton("ورقي", { paper = true }, paper, compact = true); PdfButton("فاتح", { paper = false }, compact = true) }
                Text("الخط", color = PdfMuted); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfButton("أميري", { amiri = true }, amiri, modifier = Modifier.weight(1f), compact = true); PdfButton("القاهرة", { amiri = false }, !amiri, modifier = Modifier.weight(1f), compact = true) }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (indexSheet) ModalBottomSheet(onDismissRequest = { indexSheet = false }, containerColor = PdfSurface) { Column(Modifier.padding(20.dp)) { Text("فهرس الفصول", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); chapters.forEachIndexed { i, ch -> Text("${ch.number}. ${ch.title}", color = if (i == index) PdfPurpleSoft else Color.White, modifier = Modifier.fillMaxWidth().clickable { index = i; indexSheet = false }.padding(vertical = 12.dp)) } } }
    }
}

@Composable private fun CommunityNovelEditor(initial: CommunityNovelApi.Novel?, onBack: () -> Unit, onSaved: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var categories by remember { mutableStateOf(initial?.tags?.ifEmpty { setOf(initial.category) } ?: setOf("fantasy")) }
    var completion by remember { mutableStateOf(initial?.completionStatus ?: "ongoing") }
    var cover by remember { mutableStateOf(initial?.coverPath) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) { encodeCover(context, uri) }
            if (cover == null) message = "تعذر قراءة الصورة أو حجمها غير مناسب"
        }
    }
    fun save(andChapter: Boolean) {
        if (title.isBlank()) { message = "اكتب عنوان الرواية أولًا"; return }
        if (categories.isEmpty()) { message = "اختر تصنيفًا واحدًا على الأقل"; return }
        scope.launch {
            val r = withContext(Dispatchers.IO) { CommunityNovelApi.saveNovel(context, initial, title.trim(), description.trim(), categories.first(), "ar", completion, categories, cover) }
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
    var number by remember { mutableStateOf((initial?.number ?: 1).toString()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var previousContent by remember { mutableStateOf<String?>(null) }
    var redoContent by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    PdfPage("عنوان الرواية", onBack) {
        Text("الفصل ${number.ifBlank { "٠٠" }}", color = PdfMuted, fontSize = 11.sp)
        PdfInput(number, { number = it.filter(Char::isDigit) }, "رقم الفصل", "٠٠", 50)
        PdfInput(title, { title = it }, "عنوان الفصل", "عنوان الفصل", 58)
        OutlinedTextField(content, { previousContent = content; redoContent = null; content = it }, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), placeholder = { Text("ابدأ كتابة فصلك هنا...", color = PdfMuted) }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 17.sp, lineHeight = 30.sp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = PdfPurple, unfocusedContainerColor = PdfBg, focusedContainerColor = PdfBg))
        message?.let { Text(it, color = Color(0xFFFFC857), fontSize = 11.sp) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { PdfButton("تراجع", { previousContent?.let { redoContent = content; content = it; previousContent = null } }, compact = true); PdfButton("إعادة", { redoContent?.let { previousContent = content; content = it; redoContent = null } }, compact = true); Spacer(Modifier.weight(1f)); PdfButton("حفظ كمسودة", { saveChapter(context, scope, novel, initial, number, title, content, false, { message = it }, onSaved) }, compact = true); PdfButton("معاينة", { preview = true }, compact = true); PdfButton("نشر", { saveChapter(context, scope, novel, initial, number, title, content, true, { message = it }, onSaved) }, true, compact = true) }
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
