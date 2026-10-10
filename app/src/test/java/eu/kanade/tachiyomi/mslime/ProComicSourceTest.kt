package eu.kanade.tachiyomi.mslime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProComicSourceTest {
    @Test
    fun extractsAndOrdersAbsoluteProtocolRelativeAndRelativeImageUrls() {
        val html = """
            <img src="https://app.procomic.pro/chapters/633/40477/p10/image-desktop.avif">
            <img src="//app.procomic.pro/chapters/633/40477/p2/image-desktop.avif">
            <img src="/chapters/633/40477/p1/image-desktop.avif">
        """.trimIndent()

        assertEquals(
            listOf(
                "https://app.procomic.pro/chapters/633/40477/p1/image-desktop.avif",
                "https://app.procomic.pro/chapters/633/40477/p2/image-desktop.avif",
                "https://app.procomic.pro/chapters/633/40477/p10/image-desktop.avif",
            ),
            extractProComicPageUrls(html),
        )
    }

    @Test
    fun cachesReaderCapturedPagesInNumericOrder() {
        val source = ProComicSource()
        val chapterUrl = "https://procomic.pro/ar/chapter/example-1-40477"
        val chapterPath = "/ar/chapter/example-1-40477"
        val page1 = "https://app.procomic.pro/chapters/633/40477/p1/image-desktop.avif"
        val page2 = "https://app.procomic.pro/chapters/633/40477/p2/image-desktop.avif"
        val page3 = "https://app.procomic.pro/chapters/633/40477/p3/image-desktop.avif"

        source.cacheReaderPageUrls(chapterUrl, listOf(page3))
        source.cacheReaderPageUrls(chapterUrl, listOf(page1, page2))

        assertEquals(listOf(page1, page2, page3), source.cachedReaderPageUrls(chapterPath))
    }

    @Test
    fun onlyMarksAChapterReadyAfterItsReaderPagesWereCaptured() {
        val source = ProComicSource()
        val chapterUrl = "https://procomic.pro/ar/chapter/example-1-40477"
        val page = "https://app.procomic.pro/chapters/633/40477/p1/image-desktop.avif"

        assertEquals(false, source.markReaderPageListComplete(chapterUrl))
        source.cacheReaderPageUrls(chapterUrl, listOf(page))
        assertEquals(true, source.markReaderPageListComplete(chapterUrl))
    }

    @Test
    fun mergesManyIncrementalPageBatchesWithoutTruncatingTheChapter() {
        val source = ProComicSource()
        val chapterUrl = "https://procomic.pro/ar/chapter/example-1-40477"
        val chapterPath = "/ar/chapter/example-1-40477"
        val pages = (1..40).map { page ->
            "https://app.procomic.pro/chapters/633/40477/p$page/image-desktop.avif"
        }

        pages.chunked(4).forEach { batch -> source.cacheReaderPageUrls(chapterUrl, batch) }

        assertEquals(pages, source.cachedReaderPageUrls(chapterPath))
        assertEquals(true, source.markReaderPageListComplete(chapterUrl))
    }

    @Test
    fun rejectsReaderImagesBelongingToAnotherChapter() {
        val source = ProComicSource()
        val chapterUrl = "https://procomic.pro/ar/chapter/example-1-40477"
        val chapterPath = "/ar/chapter/example-1-40477"
        val wrongChapterPage = "https://app.procomic.pro/chapters/633/40478/p1/image.avif"

        source.cacheReaderPageUrls(chapterUrl, listOf(wrongChapterPage))

        assertNull(source.cachedReaderPageUrls(chapterPath))
    }
}
