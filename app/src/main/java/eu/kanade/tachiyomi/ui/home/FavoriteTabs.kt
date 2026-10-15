package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.tab.TabOptions
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.mslime.CommunityNovelOpenScreen
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavoriteItem
import eu.kanade.tachiyomi.mslime.MslFavoriteListScreen
import eu.kanade.tachiyomi.novel.NovelDetailsScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.app.di.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.CollectionsBookmark

private class FavoritesDestination(
    private val index: UShort,
    private val label: String,
    private val kind: MslFavoriteKind,
    private val icon: @Composable () -> androidx.compose.ui.graphics.painter.Painter,
) : Tab {
    override val options: TabOptions
        @Composable
        get() = TabOptions(index = index, title = label, icon = icon())

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        MslFavoriteListScreen(kind) { item ->
            when (item.kind) {
                MslFavoriteKind.MANGA -> {
                    val sourceId = item.sourceId?.toLongOrNull()
                    if (sourceId == null) return@MslFavoriteListScreen
                    scope.launch {
                        val manga = withContext(Dispatchers.IO) {
                            context.appGraph.getMangaByUrlAndSourceId.await(item.itemKey, sourceId)
                        }
                        if (manga != null) navigator.push(MangaScreen(manga.id))
                    }
                }
                MslFavoriteKind.NOVEL -> {
                    val parts = item.itemKey.split("::", limit = 2)
                    if (parts.size == 2) navigator.push(NovelDetailsScreen(parts[0], parts[1], item.title))
                }
                MslFavoriteKind.COMMUNITY -> navigator.push(CommunityNovelOpenScreen(item.itemKey))
            }
        }
    }
}

val FavoriteMangaTab: Tab = FavoritesDestination(
    index = 6u.toUShort(),
    label = MslFavoriteKind.MANGA.label,
    kind = MslFavoriteKind.MANGA,
) { rememberVectorPainter(MaterialSymbols.Rounded.CollectionsBookmark) }

val FavoriteNovelTab: Tab = FavoritesDestination(
    index = 7u.toUShort(),
    label = MslFavoriteKind.NOVEL.label,
    kind = MslFavoriteKind.NOVEL,
) { rememberVectorPainter(MaterialSymbols.AutoMirroredRounded.ChromeReaderMode) }

val FavoriteCommunityTab: Tab = FavoritesDestination(
    index = 8u.toUShort(),
    label = MslFavoriteKind.COMMUNITY.label,
    kind = MslFavoriteKind.COMMUNITY,
) { rememberVectorPainter(MaterialSymbols.Rounded.CollectionsBookmark) }
