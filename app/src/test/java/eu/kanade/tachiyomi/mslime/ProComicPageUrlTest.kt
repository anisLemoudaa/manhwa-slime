package eu.kanade.tachiyomi.mslime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProComicPageUrlTest {
    @Test
    fun ordersDirectoryStylePageNumbersNumerically() {
        val page1 = "https://app.procomic.pro/chapters/633/40477/p1/image-desktop.avif"
        val page2 = "https://app.procomic.pro/chapters/633/40477/p2/image-desktop.avif"
        val page10 = "https://app.procomic.pro/chapters/633/40477/p10/image-desktop.avif"

        assertEquals(
            listOf(page1, page2, page10),
            orderProComicPageUrls(listOf(page10, page2, page1)),
        )
    }

    @Test
    fun prefersDesktopVariantForDuplicatePageNumber() {
        val page1Desktop = "https://app.procomic.pro/chapters/633/40477/p1/image-desktop.avif"
        val page1Mobile = "https://app.procomic.pro/chapters/633/40477/p1/image-mobile.avif"
        val page2Desktop = "https://app.procomic.pro/chapters/633/40477/p2/image-desktop.avif"

        assertEquals(
            listOf(page1Desktop, page2Desktop),
            orderProComicPageUrls(listOf(page2Desktop, page1Mobile, page1Desktop)),
        )
    }

    @Test
    fun keepsUnnumberedImageUrlsAfterSortedPages() {
        val page1 = "https://app.procomic.pro/chapters/633/40477/p1/image.avif"
        val unknown = "https://app.procomic.pro/chapters/633/40477/cover.avif"

        assertEquals(
            listOf(page1, unknown),
            orderProComicPageUrls(listOf(unknown, page1)),
        )
    }
}
