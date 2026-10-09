package eu.kanade.presentation.webview

import java.net.URI
import java.util.Locale

/** Ad-free WebView policy for ProComic chapter reading only. */
internal object ProComicAdPolicy {
    private val adProviderDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "adform.net",
    )

    fun shouldBlockResource(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host.normalizedHost()
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val firstPartyAdEndpoint = host.isProComicHost() && (
            path == "/api/ads" || path.startsWith("/api/ads/") ||
                path == "/api/advertising" || path.startsWith("/api/advertising/")
            )

        return firstPartyAdEndpoint || adProviderDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    fun isAllowedMainFrame(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.normalizedHost().isProComicHost()
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun String?.normalizedHost(): String = this.orEmpty().trimEnd('.').lowercase(Locale.ROOT)

    private fun String.isProComicHost(): Boolean = this == "procomic.pro" || endsWith(".procomic.pro")

    val hideAdsScript: String = """
        (function() {
            const selectors = [
                '[data-ad]', '[data-ad-slot]', '[data-ad-client]', '[data-testid*="ad-"]',
                'ins.adsbygoogle', '[class*="adsbygoogle"]',
                '[id*="ad-container"]', '[class*="ad-container"]',
                '[id*="advertisement"]', '[class*="advertisement"]',
                '[id*="sponsored"]', '[class*="sponsored"]',
                'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication.com"]',
                'iframe[src*="googleadservices.com"]',
                'img[src*="doubleclick.net"]', 'img[src*="googlesyndication.com"]',
                'img[src*="googleadservices.com"]'
            ].join(',');
            const style = document.createElement('style');
            style.textContent = selectors + '{display:none!important;visibility:hidden!important;'
                + 'width:0!important;height:0!important;pointer-events:none!important}';
            (document.head || document.documentElement).appendChild(style);
            const removeAds = function() {
                document.querySelectorAll(selectors).forEach(function(element) { element.remove(); });
            };
            removeAds();
            new MutationObserver(removeAds).observe(document.documentElement, {childList:true,subtree:true});
        })();
    """.trimIndent()
}
