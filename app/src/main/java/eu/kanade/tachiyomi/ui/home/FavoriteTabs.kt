package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.mslime.CommunityNovelOpenScreen
import eu.kanade.tachiyomi.mslime.MslFavoriteItem
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavoriteListScreen
import eu.kanade.tachiyomi.novel.NovelDetailsScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import androidx.compose.ui.graphics.vector.rememberVectorPainter

private abstract class FavoritesDestination(
    private val index: Int,
    private val kind: MslFavoriteKind,
) : Tab {
    // Distinct, stable keys prevent Voyager from restoring several tabs into the same currentTab slot.
    override val key: ScreenKey = "favorite-${kind.wireType}"

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = index.toUShort(),
            title = kind.label,
            icon = rememberVectorPainter(
                when (kind) {
                    MslFavoriteKind.NOVEL -> MaterialSymbols.AutoMirroredRounded.ChromeReaderMode
                    MslFavoriteKind.MANGA, MslFavoriteKind.COMMUNITY -> MaterialSymbols.Rounded.CollectionsBookmark
                },
            ),
        )

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        MslFavoriteListScreen(kind) { item: MslFavoriteItem ->
            when (item.kind) {
                MslFavoriteKind.MANGA -> {
                    val sourceId = item.sourceId?.toLongOrNull() ?: return@MslFavoriteListScreen
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

private object FavoriteMangaDestination : FavoritesDestination(6, MslFavoriteKind.MANGA)
private object FavoriteNovelDestination : FavoritesDestination(7, MslFavoriteKind.NOVEL)
private object FavoriteCommunityDestination : FavoritesDestination(8, MslFavoriteKind.COMMUNITY)

val FavoriteMangaTab: Tab = FavoriteMangaDestination
val FavoriteNovelTab: Tab = FavoriteNovelDestination
val FavoriteCommunityTab: Tab = FavoriteCommunityDestination
