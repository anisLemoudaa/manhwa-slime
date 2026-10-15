package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.mslime.MslFavoriteKind
import eu.kanade.tachiyomi.mslime.MslFavoriteListScreen
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
        MslFavoriteListScreen(kind)
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
