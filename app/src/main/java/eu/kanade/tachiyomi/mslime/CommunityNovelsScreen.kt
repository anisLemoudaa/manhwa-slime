@file:Suppress("ktlint:standard:no-wildcard-imports")
package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() { scope.launch { novels = withContext(Dispatchers.IO) { CommunityNovelApi.list(context, filter, query) }; mine = withContext(Dispatchers.IO) { CommunityNovelApi.mine(context) } } }
    LaunchedEffect(filter, query) { reload() }

    when (page) {
        CommunityPage.HOME -> CommunityHome(
            novels = novels,
            mine = mine,
            filter = filter,
            query = query,
            onQuery = { query = it },
            onFilter = { filter = it },
            onOpen = { selected = it; page = CommunityPage.DETAILS },
            onCreate = { selected = null; page = CommunityPage.EDIT_NOVEL },
            onEdit = { selected = it; page = CommunityPage.EDIT_NOVEL },
        )
        CommunityPage.DETAILS -> selected?.let { novel ->
            CommunityDetails(
                novel = novel,
                onBack = { page = CommunityPage.HOME },
                onRead = { chapter -> selectedChapter = chapter; page = CommunityPage.READER },
                onEdit = { page = CommunityPage.EDIT_NOVEL },
                onNewChapter = { selectedChapter = null; page = CommunityPage.EDIT_CHAPTER },
                onRefresh = { reload() },
            )
        } ?: run { page = CommunityPage.HOME }
        CommunityPage.READER -> selected?.let { novel ->
            CommunityReader(novel, selectedChapter, onBack = { page = CommunityPage.DETAILS })
        } ?: run { page = CommunityPage.HOME }
        CommunityPage.EDIT_NOVEL -> CommunityNovelEditor(
            initial = selected,
            onBack = { page = if (selected == null) CommunityPage.HOME else CommunityPage.DETAILS },
            onSaved = { id -> scope.launch { selected = withContext(Dispatchers.IO) { CommunityNovelApi.details(context, id) }; reload(); page = CommunityPage.DETAILS } },
        )
        CommunityPage.EDIT_CHAPTER -> selected?.let { novel ->
            CommunityChapterEditor(novel, selectedChapter, onBack = { page = CommunityPage.DETAILS }, onSaved = { reload(); page = CommunityPage.DETAILS })
        } ?: run { page = CommunityPage.HOME }
    }
    error?.let { message -> LaunchedEffect(message) { error = null } }
}

@Composable private fun CommunityShell(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(MslDesignTokens.background).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) Text("‹", color = MslDesignTokens.textPrimary, fontSize = 32.sp, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                Text(title, color = MslDesignTokens.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable private fun CommunityHome(novels: List<CommunityNovelApi.Novel>, mine: List<CommunityNovelApi.Novel>, filter: String, query: String, onQuery: (String) -> Unit, onFilter: (String) -> Unit, onOpen: (CommunityNovelApi.Novel) -> Unit, onCreate: () -> Unit, onEdit: (CommunityNovelApi.Novel) -> Unit) {
    CommunityShell("روايات المجتمع") {
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text("+ إنشاء رواية جديدة") }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ابحث باسم الرواية أو المؤلف") })
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("latest" to "الأحدث", "popular" to "الأكثر قراءة", "liked" to "الأكثر إعجابًا").forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = { Text(label, maxLines = 1, fontSize = 11.sp) })
            }
        }
        if (mine.isNotEmpty()) {
            Text("رواياتي", color = MslDesignTokens.accentBright, fontWeight = FontWeight.Bold)
            mine.take(3).forEach { NovelCard(it, onOpen, onEdit) }
            Spacer(Modifier.height(8.dp))
        }
        Text("الروايات المنشورة", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold)
        if (novels.isEmpty()) Text("لا توجد روايات منشورة بعد. كن أول من يكتب رواية في المجتمع.", color = MslDesignTokens.textSecondary, modifier = Modifier.padding(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 30.dp)) { items(novels, key = { it.id }) { NovelCard(it, onOpen, {}) } }
    }
}

@Composable private fun NovelCard(novel: CommunityNovelApi.Novel, onOpen: (CommunityNovelApi.Novel) -> Unit, onEdit: (CommunityNovelApi.Novel) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(novel) }, colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface), shape = MslDesignTokens.cardShape) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(82.dp, 108.dp).background(MslDesignTokens.surfaceHighest, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("▣", color = MslDesignTokens.accentBright, fontSize = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text(novel.title, color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("مؤلف المجتمع", color = MslDesignTokens.textSecondary, fontSize = 12.sp)
                Text(novel.description.ifBlank { "لا يوجد وصف مختصر للرواية." }, color = MslDesignTokens.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AssistChip(onClick = {}, label = { Text(categoryLabel(novel.category), fontSize = 10.sp) }); AssistChip(onClick = {}, label = { Text(if (novel.completionStatus == "completed") "مكتملة" else "مستمرة", fontSize = 10.sp) }) }
                Text("${novel.chapterCount} فصل  •  ${novel.viewCount} قراءة", color = MslDesignTokens.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable private fun CommunityDetails(novel: CommunityNovelApi.Novel, onBack: () -> Unit, onRead: (CommunityNovelApi.Chapter) -> Unit, onEdit: () -> Unit, onNewChapter: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember(novel.id) { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var liked by remember { mutableStateOf(novel.liked) }
    var saved by remember { mutableStateOf(novel.inLibrary) }
    LaunchedEffect(novel.id) { chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id, novel.authorId == MslSupabase.uid(context)) } }
    CommunityShell(novel.title, onBack) {
        Text("مؤلف المجتمع", color = MslDesignTokens.textSecondary)
        Text("${categoryLabel(novel.category)}  •  ${if (novel.completionStatus == "completed") "مكتملة" else "مستمرة"}", color = MslDesignTokens.accentBright, modifier = Modifier.padding(vertical = 8.dp))
        Text(novel.description.ifBlank { "لا يوجد وصف للرواية." }, color = MslDesignTokens.textPrimary, lineHeight = 24.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chapters.firstOrNull()?.let(onRead) }, enabled = chapters.isNotEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text("ابدأ القراءة") }
            OutlinedButton(onClick = { saved = !saved; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleLibrary(context, novel.id, saved) } } }) { Text(if (saved) "في المكتبة" else "المكتبة") }
            OutlinedButton(onClick = { liked = !liked; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.toggleLike(context, novel.id, liked) } } }) { Text(if (liked) "♥" else "♡") }
        }
        if (novel.authorId == MslSupabase.uid(context)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("تعديل") }
                OutlinedButton(onClick = onNewChapter) { Text("+ فصل") }
                if (novel.status != "published") {
                    Button(onClick = { scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.setNovelStatus(context, novel.id, "published") }; onRefresh() } }, colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text("نشر") }
                }
            }
        }
        Text("الفصول المنشورة (${chapters.count { it.status == "published" }})", color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        LazyColumn { items(chapters.filter { it.status == "published" }, key = { it.id }) { chapter -> ListItem(headlineContent = { Text("${chapter.number}. ${chapter.title}", color = MslDesignTokens.textPrimary) }, supportingContent = { Text("اضغط للقراءة", color = MslDesignTokens.textMuted) }, modifier = Modifier.clickable { onRead(chapter) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
    }
}

@Composable private fun CommunityReader(novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf(emptyList<CommunityNovelApi.Chapter>()) }
    var index by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableFloatStateOf(19f) }
    LaunchedEffect(novel.id) { chapters = withContext(Dispatchers.IO) { CommunityNovelApi.chapters(context, novel.id) }; index = chapters.indexOfFirst { it.id == initial?.id }.coerceAtLeast(0) }
    val chapter = chapters.getOrNull(index)
    CommunityShell(chapter?.title ?: "القارئ", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28f) }) { Text("تكبير") }; TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14f) }) { Text("تصغير") } }
        if (chapter == null) CircularProgressIndicator() else {
            Text("${novel.title}  •  الفصل ${chapter.number}", color = MslDesignTokens.accentBright, fontSize = 13.sp)
            Text(chapter.content, color = MslDesignTokens.textPrimary, fontSize = fontSize.sp, lineHeight = (fontSize * 1.75f).sp, modifier = Modifier.padding(vertical = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(enabled = index > 0, onClick = { index--; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } }) { Text("السابق") }; TextButton(enabled = index < chapters.lastIndex, onClick = { index++; scope.launch { withContext(Dispatchers.IO) { CommunityNovelApi.saveProgress(context, novel.id, chapters[index].id, 0) } } }) { Text("التالي") } }
        }
    }
}

@Composable private fun CommunityNovelEditor(initial: CommunityNovelApi.Novel?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: "fantasy") }
    var message by remember { mutableStateOf<String?>(null) }
    CommunityShell(if (initial == null) "إنشاء رواية" else "تعديل الرواية", onBack) {
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("عنوان الرواية *") }, singleLine = true)
        OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp), label = { Text("الوصف") })
        Text("التصنيف", color = MslDesignTokens.textSecondary, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) { listOf("fantasy" to "فانتازيا", "romance" to "رومانسية", "action" to "أكشن", "mystery" to "غموض").forEach { (v, l) -> FilterChip(category == v, { category = v }, label = { Text(l) }) } }
        message?.let { Text(it, color = MslDesignTokens.warning, modifier = Modifier.padding(8.dp)) }
        Button(onClick = { if (title.isBlank()) message = "اكتب عنوان الرواية أولًا" else scope.launch { val result = withContext(Dispatchers.IO) { CommunityNovelApi.saveNovel(context, initial, title, description, category, "ar", initial?.completionStatus ?: "ongoing") }; result.fold(onSaved) { message = it.message } } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text("حفظ المسودة") }
        if (initial != null) Text("يمكن نشر الرواية من صفحة التفاصيل بعد إضافة فصل منشور.", color = MslDesignTokens.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable private fun CommunityChapterEditor(novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf((initial?.number ?: 1).toString()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    CommunityShell(if (initial == null) "إضافة فصل" else "تعديل الفصل", onBack) {
        OutlinedTextField(number, { number = it.filter(Char::isDigit) }, label = { Text("رقم الفصل") }, singleLine = true)
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("عنوان الفصل") }, singleLine = true)
        OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), label = { Text("محتوى الفصل") })
        message?.let { Text(it, color = MslDesignTokens.warning) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { saveChapter(context, scope, novel, initial, number, title, content, false, { message = it }, onSaved) }, modifier = Modifier.weight(1f)) { Text("حفظ مسودة") }
            Button(onClick = { saveChapter(context, scope, novel, initial, number, title, content, true, { message = it }, onSaved) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text("نشر") }
        }
    }
}

private fun saveChapter(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, novel: CommunityNovelApi.Novel, initial: CommunityNovelApi.Chapter?, number: String, title: String, content: String, publish: Boolean, onError: (String) -> Unit, onSaved: () -> Unit) {
    if (title.isBlank() || content.isBlank()) { onError("العنوان والمحتوى مطلوبان"); return }
    scope.launch { val result = withContext(Dispatchers.IO) { CommunityNovelApi.saveChapter(context, novel.id, initial, number.toIntOrNull() ?: 1, title, content, publish) }; result.fold({ onSaved() }) { onError(it.message ?: "تعذر الحفظ") } }
}

private fun categoryLabel(value: String): String = mapOf("fantasy" to "فانتازيا", "romance" to "رومانسية", "action" to "أكشن", "adventure" to "مغامرة", "mystery" to "غموض", "horror" to "رعب", "drama" to "دراما", "sci_fi" to "خيال علمي", "comedy" to "كوميديا", "historical" to "تاريخي")[value] ?: "أخرى"
