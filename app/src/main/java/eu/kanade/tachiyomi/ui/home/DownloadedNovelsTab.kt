package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.novel.DownloadedNovelsContent
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Download

data object DownloadedNovelsTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 9u,
            title = "الروايات المحملة",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Download),
        )

    @Composable
    override fun Content() {
        DownloadedNovelsContent()
    }
}
