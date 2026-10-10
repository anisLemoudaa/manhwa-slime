package eu.kanade.tachiyomi.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.more.stats.RankSection
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.mslime.MslCloudSync
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.mslime.MslWalletScreen
import eu.kanade.tachiyomi.novel.NovelHistoryEntry
import eu.kanade.tachiyomi.novel.NovelHistoryStore
import eu.kanade.tachiyomi.novel.NovelManagerHolder
import eu.kanade.tachiyomi.novel.NovelReaderScreen
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.stats.StatsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AttachMoney
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.manga.model.MangaCover
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    Card(
        modifier = modifier,
        shape = MslDesignTokens.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = value,
                fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MslDesignTokens.accentBright,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MslDesignTokens.textSecondary,
            )
        }
    }
}

@Composable
private fun ProfileActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = MslDesignTokens.compactCardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MslDesignTokens.accentBright)
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MslDesignTokens.textSecondary)
            }
        }
    }
}

private data class UnifiedHistoryRow(
    val title: String,
    val subtitle: String,
    val readAt: Long,
    val mangaCover: MangaCover? = null,
    val mangaId: Long? = null,
    val chapterId: Long? = null,
    val novelEntry: NovelHistoryEntry? = null,
)

private fun historyDate(time: Long): String {
    return runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))
    }.getOrDefault("")
}

@Composable
private fun UnifiedHistorySection(
    historyState: HistoryViewModel.State,
    novelHistory: List<NovelHistoryEntry>,
    onOpenManga: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenNovel: (NovelHistoryEntry) -> Unit,
) {
    val mangaRows = historyState.list.orEmpty().mapNotNull { item ->
        val history = (item as? HistoryUiModel.Item)?.item ?: return@mapNotNull null

        UnifiedHistoryRow(
            title = history.title,
            subtitle = if (history.chapterNumber >= 0) {
                "مانغا • الفصل ${history.chapterNumber}"
            } else {
                "مانغا"
            },
            readAt = history.readAt?.time ?: 0L,
            mangaCover = history.coverData,
            mangaId = history.mangaId,
            chapterId = history.chapterId,
        )
    }

    val novelRows = novelHistory.map {
        UnifiedHistoryRow(
            title = it.novelTitle,
            subtitle = "رواية • ${it.chapterName}",
            readAt = it.readAt,
            mangaCover = null,
            novelEntry = it,
        )
    }

    val rows = (mangaRows + novelRows)
        .sortedByDescending { it.readAt }
        .take(15)

    if (rows.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "سجل القراءة",
            fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MslDesignTokens.textPrimary,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MslDesignTokens.cardShape,
            colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MslDesignTokens.border),
                        )
                    }
                    val openManga = row.mangaId?.let { mangaId ->
                        row.chapterId?.let { chapterId ->
                            { onOpenManga(mangaId, chapterId) }
                        }
                    }
                    val openNovel = row.novelEntry?.let { entry ->
                        { onOpenNovel(entry) }
                    }
                    val openRow = openManga ?: openNovel

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = openRow != null) { openRow?.invoke() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.mangaCover != null) {
                            eu.kanade.presentation.manga.components.MangaCover.Book(
                                modifier = Modifier.size(width = 52.dp, height = 72.dp),
                                data = row.mangaCover,
                                onClick = { openManga?.invoke() },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(width = 52.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MslDesignTokens.surfaceHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "رواية",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MslDesignTokens.accentBright,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = row.title,
                                fontWeight = FontWeight.Bold,
                                color = MslDesignTokens.textPrimary,
                                maxLines = 2,
                            )

                            Text(
                                text = row.subtitle,
                                color = MslDesignTokens.accentBlue,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )

                            Text(
                                text = historyDate(row.readAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MslDesignTokens.textMuted,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private data class UnifiedReadingItem(
    val title: String,
    val chapter: String,
    val timestamp: Long,
    val type: String,
    val coverUrl: String? = null,
    val mangaCover: tachiyomi.domain.manga.model.MangaCover? = null,
)

private fun unifiedHistoryDate(timestamp: Long): String {
    return runCatching {
        SimpleDateFormat(
            "yyyy/MM/dd HH:mm",
            Locale.getDefault(),
        ).format(Date(timestamp))
    }.getOrDefault("")
}

@Composable
private fun UnifiedReadingHistorySection(
    historyState: HistoryViewModel.State,
    novelHistory: List<NovelHistoryEntry>,
) {
    val mangaItems = historyState.list.orEmpty().mapNotNull { model ->
        val history = (model as? HistoryUiModel.Item)?.item
            ?: return@mapNotNull null

        UnifiedReadingItem(
            title = history.title,
            chapter = if (history.chapterNumber >= 0) {
                "الفصل ${history.chapterNumber}"
            } else {
                "آخر قراءة"
            },
            timestamp = history.readAt?.time ?: 0L,
            type = "مانغا",
            mangaCover = history.coverData,
        )
    }

    val novelItems = novelHistory.map { entry ->
        UnifiedReadingItem(
            title = entry.novelTitle,
            chapter = "الفصل: ${entry.chapterName}",
            timestamp = entry.readAt,
            type = "رواية",
            coverUrl = entry.cover,
        )
    }

    val items = (mangaItems + novelItems)
        .filter { it.timestamp > 0L }
        .sortedByDescending { it.timestamp }
        .take(15)

    if (items.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "📖 سجل القراءة",
            fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            item.mangaCover != null -> {
                                eu.kanade.presentation.manga.components.MangaCover.Book(
                                    modifier = Modifier.size(
                                        width = 48.dp,
                                        height = 68.dp,
                                    ),
                                    data = item.mangaCover,
                                    onClick = {},
                                )
                            }

                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(
                                            width = 48.dp,
                                            height = 68.dp,
                                        )
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            MaterialTheme.colorScheme
                                                .secondaryContainer,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "📖",
                                        fontSize = 22.sp,
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = "${item.type} • ${item.chapter}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = unifiedHistoryDate(item.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

data object ProfileTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 4u,
            title = "الملف الشخصي",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Person),
        )

    @Composable
    override fun Content() {
        eu.kanade.tachiyomi.mslime.MslThemed { ProfileContent() }
    }

    @Composable
    private fun ProfileContent() {
        val ctx = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val scope = rememberCoroutineScope()
        val viewModel = metroViewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
        val historyViewModel = metroViewModel<HistoryViewModel>()
        val historyState by historyViewModel.state.collectAsState()
        val novelHistory = remember(ctx) {
            NovelHistoryStore(ctx).all()
        }
        var rev by remember { mutableIntStateOf(0) }
        var vipStatus by remember { mutableStateOf<eu.kanade.tachiyomi.mslime.MslVipStatus?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var editName by remember { mutableStateOf(false) }
        var cloudLevel by remember { mutableIntStateOf(1) }
        var name by remember {
            mutableStateOf(prefs(ctx).getString("name", "قارئ السلايم") ?: "قارئ السلايم")
        }
        val joined = remember {
            val p = prefs(ctx)
            var j = p.getLong("joined", 0L)
            if (j == 0L) {
                j = System.currentTimeMillis()
                p.edit().putLong("joined", j).apply()
            }
            j
        }
        LaunchedEffect(ctx) {
            withContext(Dispatchers.IO) { MslCloudSync.profile(ctx) }?.let { remote ->
                MslCloudSync.applyProfile(ctx, remote)
                name = remote.name
                cloudLevel = remote.level
                rev++
            }
            vipStatus = withContext(Dispatchers.IO) {
                eu.kanade.tachiyomi.mslime.MslVip.refresh(ctx)
                    ?: if (eu.kanade.tachiyomi.mslime.MslVip.cachedActive(ctx)) eu.kanade.tachiyomi.mslime.MslVipStatus(active = true) else null
            }
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
        val level = maxOf(read / 25 + 1, cloudLevel)
        androidx.compose.runtime.SideEffect { prefs(ctx).edit().putInt("read", read).putInt("cloud_level", level).apply() }
        LaunchedEffect(name, rev, level, read) {
            withContext(Dispatchers.IO) {
                MslCloudSync.saveProfile(
                    ctx,
                    name,
                    MslCloudSync.imageData(ctx, "msl_avatar.img"),
                    MslCloudSync.imageData(ctx, "msl_cover.img"),
                    level,
                    eu.kanade.tachiyomi.mslime.MslRank.of(read),
                )
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MslDesignTokens.background)
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(218.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(MslDesignTokens.surfaceHighest, MslDesignTokens.background),
                            ),
                        )
                        .clickable { coverPicker.launch("image/*") },
                ) {
                    if (cover != null) {
                        Image(
                            bitmap = cover.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                            .background(MslDesignTokens.heroGradient),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp)
                            .offset(y = 54.dp)
                            .size(104.dp)
                            .clip(CircleShape)
                            .border(3.dp, MslDesignTokens.accent, CircleShape)
                            .background(MslDesignTokens.backgroundRaised)
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
                Spacer(modifier = Modifier.height(66.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = name,
                            fontSize = 24.sp,
                            fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
                            fontWeight = FontWeight.Bold,
                            color = MslDesignTokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { editName = true },
                        )
                        if (vipStatus?.active == true) {
                            Surface(shape = MslDesignTokens.pillShape, color = Color(0xFFFFC857).copy(alpha = 0.18f)) {
                                Text("VIP", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color(0xFFFFC857), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Surface(shape = MslDesignTokens.pillShape, color = MslDesignTokens.accent.copy(alpha = 0.22f)) {
                        Text(
                            text = "المستوى $level",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MslDesignTokens.accentBright,
                        )
                    }
                }
                Text(
                    text = joinedText(joined),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MslDesignTokens.textSecondary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileActionCard(
                        title = "المحفظة",
                        subtitle = "الرصيد والمتجر",
                    icon = MaterialSymbols.Rounded.AttachMoney,
                        modifier = Modifier.weight(1f),
                        onClick = { navigator.push(MslWalletScreen()) },
                    )
                    ProfileActionCard(
                        title = "الإعدادات والمزيد",
                        subtitle = "التنزيلات والدعم",
                        icon = MaterialSymbols.Rounded.Settings,
                        modifier = Modifier.weight(1f),
                        onClick = { tabNavigator.current = MoreTab },
                    )
                }
                ProfileActionCard(
                    title = "عضوية VIP",
                    subtitle = if (vipStatus?.active == true) "العضوية نشطة — إدارة المزايا" else "الخطط والمزايا اليومية",
                    icon = MaterialSymbols.Rounded.AttachMoney,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = { navigator.push(eu.kanade.tachiyomi.mslime.MslVipScreen()) },
                )

                Text(
                    text = "الحساب",
                    fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MslDesignTokens.textPrimary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                eu.kanade.tachiyomi.mslime.MslAccountCard()
                Text(
                    text = "إعدادات القارئ",
                    fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MslDesignTokens.textPrimary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                eu.kanade.tachiyomi.mslime.MslReaderSettingsCard()

                UnifiedHistorySection(
                    historyState = historyState,
                    novelHistory = novelHistory,
                    onOpenManga = { _, chapterId ->
                        scope.launch {
                            val chapter = historyViewModel.getChapterById(chapterId)
                            if (chapter != null) {
                                ctx.startActivity(
                                    ReaderActivity.newIntent(
                                        ctx,
                                        chapter.mangaId,
                                        chapter.id,
                                    ),
                                )
                            }
                        }
                    },
                    onOpenNovel = { entry ->
                        scope.launch {
                            val details = runCatching {
                                NovelManagerHolder.get(ctx).details(
                                    entry.sourceId,
                                    entry.novelPath,
                                )
                            }.getOrNull() ?: return@launch

                            val chapters = details.chapters.orEmpty()
                            if (chapters.isEmpty()) return@launch

                            val exactIndex = chapters.indexOfFirst {
                                it.path == entry.chapterPath
                            }

                            val index = when {
                                exactIndex >= 0 -> exactIndex
                                else -> entry.chapterIndex.coerceIn(0, chapters.lastIndex)
                            }

                            navigator.push(
                                NovelReaderScreen(
                                    sourceId = entry.sourceId,
                                    novelPath = entry.novelPath,
                                    novelName = entry.novelTitle,
                                    novelCover = details.cover ?: entry.cover,
                                    chapters = chapters,
                                    chapterIndex = index,
                                ),
                            )
                        }
                    },
                )
                if (s is StatsScreenState.Success) {
                    val ms = s.overview.totalReadDuration
                    Text(
                        text = "إحصاءات القراءة",
                        fontFamily = eu.kanade.tachiyomi.mslime.MslDisplayFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MslDesignTokens.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatCard("وقت القراءة", "${ms / 3_600_000}س ${ms / 60_000 % 60}د", Modifier.weight(1f))
                        StatCard("فصل مقروء", read.toString(), Modifier.weight(1f))
                    }
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MslDesignTokens.background,
                        contentColor = MslDesignTokens.accentBright,
                    ) {
                        M3Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الرتبة") })
                        M3Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("المكتبة") })
                    }
                    Surface(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MslDesignTokens.cardShape,
                        color = MslDesignTokens.surface,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (tab == 0) {
                                RankSection(s.chapters)
                            } else {
                                StatCard(
                                    "عناوين المكتبة",
                                    s.overview.libraryMangaCount.toString(),
                                    Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                StatCard(
                                    "عناوين مكتملة",
                                    s.overview.completedMangaCount.toString(),
                                    Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                StatCard(
                                    "الفصول المحمّلة",
                                    s.chapters.downloadCount.toString(),
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MslDesignTokens.accentBright)
                    }
                }
            }

            if (editName) {
                var tmp by remember { mutableStateOf(name) }
                AlertDialog(
                    onDismissRequest = { editName = false },
                    title = { Text("اسمك") },
                    text = {
                        OutlinedTextField(
                            value = tmp,
                            onValueChange = { tmp = it },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                name = tmp.trim().ifEmpty { name }
                                prefs(ctx).edit().putString("name", name).apply()
                                editName = false
                            },
                        ) { Text("حفظ") }
                    },
                    dismissButton = {
                        TextButton(onClick = { editName = false }) {
                            Text("إلغاء")
                        }
                    },
                )
            }
        }
    }
}
