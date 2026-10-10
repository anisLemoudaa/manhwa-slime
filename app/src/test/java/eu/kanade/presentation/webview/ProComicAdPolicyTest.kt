package eu.kanade.presentation.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProComicAdPolicyTest {
    @Test
    fun blocksFirstPartyAdvertisementEndpoints() {
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://procomic.pro/api/ads/config"))
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://app.procomic.pro/api/ads/eligibility"))
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://procomic.pro/api/advertising"))
    }

    @Test
    fun blocksKnownThirdPartyAdNetworks() {
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertTrue(ProComicAdPolicy.shouldBlockResource("https://static.taboola.com/libtrc/site.js"))
    }

    @Test
    fun keepsChapterAndImageRequestsAvailable() {
        assertFalse(ProComicAdPolicy.shouldBlockResource("https://procomic.pro/ar/chapter/example-1-123"))
        assertFalse(ProComicAdPolicy.shouldBlockResource("https://app.procomic.pro/chapters/example/p1/image.webp"))
        assertFalse(ProComicAdPolicy.shouldBlockResource("https://procomic.pro/api/cdn-image"))
    }

    @Test
    fun limitsAdFreeReaderNavigationToSecureProComicHosts() {
        assertTrue(ProComicAdPolicy.isAllowedMainFrame("https://procomic.pro/ar/chapter/example-1-123"))
        assertTrue(ProComicAdPolicy.isAllowedMainFrame("https://app.procomic.pro/ar/chapter/example-1-123"))
        assertFalse(ProComicAdPolicy.isAllowedMainFrame("http://procomic.pro/ar/chapter/example-1-123"))
        assertFalse(ProComicAdPolicy.isAllowedMainFrame("https://procomic.pro.attacker.example/ads"))
        assertFalse(ProComicAdPolicy.isAllowedMainFrame("https://example.com/"))
    }
}
