package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.mslime.CommunityNovelsScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CollectionsBookmark

data object CommunityNovelTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 4u,
            title = "روايات المجتمع",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.CollectionsBookmark),
        )

    @Composable override fun Content() { CommunityNovelsScreen() }
}
