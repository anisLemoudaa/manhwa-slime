package eu.kanade.tachiyomi.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.stats.RankSection
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.stats.StatsViewModel
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import java.io.File
import androidx.compose.material3.Tab as M3Tab

private fun prefs(c: Context) = c.getSharedPreferences("msl_profile", Context.MODE_PRIVATE)

private fun loadBmp(c: Context, name: String): Bitmap? {
    val f = File(c.filesDir, name)
    if (!f.exists()) return null
    val o = BitmapFactory.Options()
    o.inSampleSize = 2
    return BitmapFactory.decodeFile(f.absolutePath, o)
}

private fun saveFromUri(c: Context, uri: Uri, name: String): Boolean = try {
    c.contentResolver.openInputStream(uri)?.use { i ->
        File(c.filesDir, name).outputStream().use { o -> i.copyTo(o) }
    }
    true
} catch (e: Exception) {
    false
}

private fun joinedText(joined: Long): String {
    val days = ((System.currentTimeMillis() - joined) / 86_400_000L).toInt()
    return when {
        days < 1 -> "انضممت اليوم"
        days < 7 -> "انضممت قبل $days أيام"
        days < 60 -> "انضممت قبل ${days / 7} أسابيع"
        else -> "انضممت قبل ${days / 30} أشهر"
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

data object ProfileTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 2u,
            title = "الملف الشخصي",
            icon = painterResource(R.drawable.ic_mihon),
        )

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val viewModel = metroViewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
        var rev by remember { mutableIntStateOf(0) }
        var tab by remember { mutableIntStateOf(0) }
        var editName by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf(prefs(ctx).getString("name", "قارئ السلايم") ?: "قارئ السلايم") }
        val joined = remember {
            val p = prefs(ctx)
            var j = p.getLong("joined", 0L)
            if (j == 0L) {
                j = System.currentTimeMillis()
                p.edit().putLong("joined", j).apply()
            }
            j
        }
        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && saveFromUri(ctx, uri, "msl_avatar.img")) rev++
        }
        val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && saveFromUri(ctx, uri, "msl_cover.img")) rev++
        }
        val avatar = remember(rev) { loadBmp(ctx, "msl_avatar.img") }
        val cover = remember(rev) { loadBmp(ctx, "msl_cover.img") }
        val s = state
        val read = if (s is StatsScreenState.Success) s.chapters.readChapterCount else 0
        val level = read / 25 + 1
        androidx.compose.runtime.SideEffect { prefs(ctx).edit().putInt("read", read).apply() }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF123A5A), Color(0xFF0F0F13))))
                    .clickable { coverPicker.launch("image/*") },
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp)
                        .offset(y = 48.dp)
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F0F13))
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_mihon),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(56.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { editName = true },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "المستوى $level",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                text = joinedText(joined),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            eu.kanade.tachiyomi.mslime.MslAccountCard()
            if (s is StatsScreenState.Success) {
                val ms = s.overview.totalReadDuration
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard("وقت القراءة", "${ms / 3_600_000}س ${ms / 60_000 % 60}د", Modifier.weight(1f))
                    StatCard("فصل مقروء", read.toString(), Modifier.weight(1f))
                }
                TabRow(selectedTabIndex = tab) {
                    M3Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الرتبة") })
                    M3Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("المكتبة") })
                }
                Column(modifier = Modifier.padding(16.dp)) {
                    if (tab == 0) {
                        RankSection(s.chapters)
                    } else {
                        StatCard("عناوين المكتبة", s.overview.libraryMangaCount.toString(), Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                        StatCard("عناوين مكتملة", s.overview.completedMangaCount.toString(), Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                        StatCard("الفصول المحمّلة", s.chapters.downloadCount.toString(), Modifier.fillMaxWidth())
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (editName) {
            var tmp by remember { mutableStateOf(name) }
            AlertDialog(
                onDismissRequest = { editName = false },
                title = { Text("اسمك") },
                text = { OutlinedTextField(value = tmp, onValueChange = { tmp = it }, singleLine = true) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            name = tmp.trim().ifEmpty { name }
                            prefs(ctx).edit().putString("name", name).apply()
                            editName = false
                        },
                    ) { Text("حفظ") }
                },
                dismissButton = { TextButton(onClick = { editName = false }) { Text("إلغاء") } },
            )
        }
    }
}
