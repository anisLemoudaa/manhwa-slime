package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.novel.NovelSectionContent
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode

/** Dedicated navigation destination for the existing novel catalog and reader flow. */
data object NovelTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 3u,
            title = "الروايات",
            icon = rememberVectorPainter(MaterialSymbols.AutoMirroredRounded.ChromeReaderMode),
        )

    @Composable
    override fun Content() {
        NovelSectionContent()
    }
}
