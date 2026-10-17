package eu.kanade.tachiyomi.mslime

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novel.OfflineNovelBook
import eu.kanade.tachiyomi.novel.OfflineNovelChapter
import eu.kanade.tachiyomi.novel.NovelOfflineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommunityOfflineReaderScreen(
    private val book: OfflineNovelBook,
    private val initialChapter: OfflineNovelChapter,
) : Screen() {
    @Composable
    override fun Content() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val prefs = remember { context.getSharedPreferences("manhwa_slime_reader_prefs", android.content.Context.MODE_PRIVATE) }
        var index by remember { mutableIntStateOf(book.chapters.indexOf(initialChapter).coerceAtLeast(0)) }
        var content by remember { mutableStateOf("") }
        var locked by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var accessRevision by remember { mutableIntStateOf(0) }
        var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 20f).coerceIn(14f, 32f)) }
        var lineSpacing by remember { mutableFloatStateOf(prefs.getFloat("line_spacing", 1.8f).coerceIn(1.2f, 3f)) }
        var paper by remember { mutableStateOf(prefs.getBoolean("community_paper", false)) }
        var amiri by remember { mutableStateOf(prefs.getBoolean("community_amiri", true)) }
        var fullscreen by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }
        val chapter = book.chapters.getOrNull(index)
        val store = remember { NovelOfflineStore(context) }
        val background = if (paper) Color(0xFFF4ECD8) else Color(0xFF030611)
        val foreground = if (paper) Color(0xFF26201A) else Color(0xFFE6E8EC)

        DisposableEffect(fullscreen) {
            val decor = context.findCommunityActivity()?.window?.decorView
            decor?.systemUiVisibility = if (fullscreen) {
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            onDispose { decor?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
        }
        LaunchedEffect(chapter?.path, accessRevision) {
            content = ""
            locked = false
            val current = chapter ?: run { loading = false; return@LaunchedEffect }
            loading = true
            val key = MslChapterAccess.communityKey(book.novelPath, current.path)
            if (!withContext(Dispatchers.IO) { MslChapterAccess.hasAccess(context, key) }) {
                locked = true
                loading = false
                return@LaunchedEffect
            }
            content = withContext(Dispatchers.IO) { store.read(book.sourceId, book.novelPath, current.path) }.orEmpty()
            loading = false
        }

        Column(Modifier.fillMaxSize().background(background).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.clickable { navigator.pop() }.padding(horizontal = 8.dp), color = foreground, fontSize = 28.sp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(book.title, color = foreground.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
                    Text(chapter?.title.orEmpty(), color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text("Aa", Modifier.clickable { settings = true }.padding(8.dp), color = Color(0xFFBD75F7), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (fullscreen) "⤢" else "⛶", Modifier.clickable { fullscreen = !fullscreen }.padding(8.dp), color = Color(0xFFBD75F7), fontSize = 18.sp)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 18.dp)) {
                item {
                    Text(chapter?.title.orEmpty(), color = foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = if (amiri) MslUiFont else null)
                    when {
                        loading -> CircularProgressIndicator(color = Color(0xFFBD75F7), modifier = Modifier.padding(top = 22.dp))
                        locked -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 22.dp)) {
                            Text("هذا الفصل مقفل، حتى لو كانت له نسخة محفوظة على الجهاز.", color = foreground)
                            Button(onClick = {
                                val current = chapter ?: return@Button
                                val key = MslChapterAccess.communityKey(book.novelPath, current.path)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        MslChapterAccess.unlock(context, listOfNotNull(key))
                                    }
                                    if (result?.allowed == true) accessRevision++
                                    else Toast.makeText(context, "تعذّر فتح الفصل أو أن الرصيد غير كافٍ. تحقق من الاتصال والمحفظة.", Toast.LENGTH_LONG).show()
                                }
                            }) { Text("فتح مقابل عملة واحدة") }
                        }
                        else -> Text(content.ifBlank { "تعذّر العثور على النسخة المحفوظة لهذا الفصل." }, color = foreground, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp, fontFamily = if (amiri) MslUiFont else null, modifier = Modifier.padding(top = 22.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("السابق", Modifier.clickable { if (index > 0) index-- }.padding(12.dp), color = if (index > 0) Color(0xFFBD75F7) else foreground.copy(alpha = .4f))
                Text("${index + 1}/${book.chapters.size}", color = foreground, fontWeight = FontWeight.Bold)
                Text("التالي", Modifier.clickable { if (index < book.chapters.lastIndex) index++ }.padding(12.dp), color = if (index < book.chapters.lastIndex) Color(0xFFBD75F7) else foreground.copy(alpha = .4f))
            }
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = Color(0xFF11132F)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("إعدادات القراءة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("حجم الخط: ${fontSize.toInt()}", color = Color(0xFF9290AB))
                Slider(value = fontSize, onValueChange = { fontSize = it; prefs.edit().putFloat("font_size", it).apply() }, valueRange = 14f..32f, colors = SliderDefaults.colors(thumbColor = Color(0xFFBD75F7), activeTrackColor = Color(0xFFBD75F7)))
                Text("تباعد الأسطر: ${String.format(java.util.Locale.US, "%.1f", lineSpacing)}", color = Color(0xFF9290AB))
                Slider(value = lineSpacing, onValueChange = { lineSpacing = it; prefs.edit().putFloat("line_spacing", it).apply() }, valueRange = 1.2f..3f, colors = SliderDefaults.colors(thumbColor = Color(0xFFBD75F7), activeTrackColor = Color(0xFFBD75F7)))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تاجوال", Modifier.clickable { amiri = true; prefs.edit().putBoolean("community_amiri", true).apply() }.padding(10.dp), color = Color.White)
                    Text("افتراضي", Modifier.clickable { amiri = false; prefs.edit().putBoolean("community_amiri", false).apply() }.padding(10.dp), color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("داكن", Modifier.clickable { paper = false; prefs.edit().putBoolean("community_paper", false).apply() }.padding(10.dp), color = Color.White)
                    Text("ورقي", Modifier.clickable { paper = true; prefs.edit().putBoolean("community_paper", true).apply() }.padding(10.dp), color = Color.White)
                }
                Spacer(Modifier.padding(bottom = 20.dp))
            }
        }
    }
}
