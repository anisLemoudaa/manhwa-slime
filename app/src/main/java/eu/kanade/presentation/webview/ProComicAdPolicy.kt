package eu.kanade.presentation.webview

import java.net.URI
import java.util.Locale

/** Ad-free, image-focused WebView policy for ProComic chapter reading only. */
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

    /** Hide the surrounding site chrome while preserving ProComic's own lazy-loaded page rows. */
    val readerOnlyScript: String = """
        (function() {
            const styleId = 'mslime-procomic-reader-only-style';
            const ensureStyle = function() {
                if (document.getElementById(styleId)) return;
                const style = document.createElement('style');
                style.id = styleId;
                style.textContent = `
                    html, body { margin:0!important; padding:0!important; width:100%!important;
                        min-height:100%!important; background:#000!important; overflow-x:hidden!important; }
                    [data-reader-image-row] { width:100%!important; max-width:none!important;
                        margin:0!important; padding:0!important; border:0!important; }
                    [data-reader-image-row] img { display:block!important; width:100%!important;
                        max-width:100%!important; height:auto!important; object-fit:contain!important; }
                `;
                (document.head || document.documentElement).appendChild(style);
            };

            const containsAllRows = function(element, rows) {
                return rows.every(function(row) { return element.contains(row); });
            };

            const findNextChapterUrl = function() {
                const links = Array.from(document.querySelectorAll('a[href]'));
                const nextLink = links.find(function(link) {
                    const label = (link.textContent || '').replace(/\s+/g, '').toLowerCase();
                    const rel = (link.rel || '').toLowerCase().split(/\s+/);
                    return label === 'التالي' || label === 'next' || rel.includes('next');
                });
                if (!nextLink) return null;
                try {
                    const nextUrl = new URL(nextLink.href, window.location.href);
                    const host = nextUrl.hostname.toLowerCase();
                    const isProComic = host === 'procomic.pro' || host.endsWith('.procomic.pro');
                    if (nextUrl.protocol !== 'https:' || !isProComic ||
                        !nextUrl.pathname.includes('/chapter/') || nextUrl.pathname === window.location.pathname) {
                        return null;
                    }
                    return nextUrl.href;
                } catch (error) {
                    return null;
                }
            };

            const collectRenderedChapterPages = function() {
                const chapterPath = window.location.pathname;
                if (window.__mslimeCollectedProComicChapter !== chapterPath) {
                    window.__mslimeCollectedProComicChapter = chapterPath;
                    window.__mslimeCollectedProComicPageUrls = [];
                }
                const collected = new Set(window.__mslimeCollectedProComicPageUrls || []);
                Array.from(document.querySelectorAll('[data-reader-image-row] img'))
                    .map(function(image) { return image.currentSrc || image.src; })
                    .filter(function(rawUrl) {
                        try {
                            const imageUrl = new URL(rawUrl, window.location.href);
                            return imageUrl.protocol === 'https:' &&
                                imageUrl.hostname.toLowerCase() === 'app.procomic.pro' &&
                                imageUrl.pathname.includes('/chapters/');
                        } catch (error) {
                            return false;
                        }
                    })
                    .forEach(function(pageUrl) { collected.add(pageUrl); });
                window.__mslimeCollectedProComicPageUrls = Array.from(collected);
                return window.__mslimeCollectedProComicPageUrls;
            };

            const syncRenderedChapterPages = function() {
                const bridge = window.MslProComicDownloadBridge;
                const chapterPath = window.location.pathname;
                if (!bridge) return;
                if (window.__mslimeSyncedProComicChapter !== chapterPath) {
                    window.__mslimeSyncedProComicChapter = chapterPath;
                    window.__mslimeSyncedProComicPageCount = 0;
                }
                const pageUrls = collectRenderedChapterPages();
                const syncedCount = window.__mslimeSyncedProComicPageCount || 0;
                if (!pageUrls || pageUrls.length <= syncedCount) return;
                const newPageUrls = pageUrls.slice(syncedCount, Math.min(pageUrls.length, syncedCount + 100));
                if (newPageUrls.length === 0) return;
                try {
                    bridge.cacheChapterPages(window.location.href, JSON.stringify(newPageUrls));
                    window.__mslimeSyncedProComicPageCount = syncedCount + newPageUrls.length;
                } catch (error) {}
            };

            const cacheRenderedChapterPages = function() {
                syncRenderedChapterPages();
            };

            const applyReaderOnly = function() {
                ensureStyle();
                const main = document.querySelector('main');
                const rows = Array.from(document.querySelectorAll('[data-reader-image-row]'));
                if (!main || rows.length === 0) return;
                window.__mslimeNextChapterUrl = findNextChapterUrl();

                // Find the first parent whose single child contains every page row while
                // its other children are site chrome (title, description, controls, comments).
                let current = rows[0];
                let readerSection = null;
                while (current && current !== main) {
                    const parent = current.parentElement;
                    if (!parent) break;
                    const children = Array.from(parent.children);
                    const pageBranch = children.find(function(child) {
                        return containsAllRows(child, rows);
                    });
                    if (pageBranch && children.length > 1) {
                        readerSection = pageBranch;
                        break;
                    }
                    current = parent;
                }
                if (!readerSection) return;

                // Keep only the branch containing all chapter rows. This removes the site's
                // header, breadcrumb, chapter metadata, navigation, social links, and comments.
                current = readerSection;
                while (current && current !== document.body) {
                    const parent = current.parentElement;
                    if (!parent) break;
                    Array.from(parent.children).forEach(function(sibling) {
                        if (sibling !== current) sibling.style.setProperty('display', 'none', 'important');
                    });
                    current = parent;
                }

                // Flatten layout wrappers without moving or replacing the website's images.
                let rowContainer = rows[0];
                while (rowContainer.parentElement && !containsAllRows(rowContainer.parentElement, rows)) {
                    rowContainer = rowContainer.parentElement;
                }
                current = rowContainer
                while (current && current !== document.body) {
                    ['margin', 'padding', 'border', 'border-radius', 'box-shadow'].forEach(function(property) {
                        current.style.setProperty(property, '0', 'important');
                    });
                    current.style.setProperty('width', '100%', 'important');
                    current.style.setProperty('max-width', 'none', 'important');
                    current = current.parentElement;
                }
                document.documentElement.style.setProperty('background', '#000', 'important');
                document.body.style.setProperty('margin', '0', 'important');
                document.body.style.setProperty('padding', '0', 'important');
                document.body.style.setProperty('width', '100%', 'important');
                document.body.style.setProperty('background', '#000', 'important');

                // Keep the lazy-load sentinel in the DOM (and at its original size), but hide its text.
                readerSection.querySelectorAll('div').forEach(function(element) {
                    if (element.children.length === 0 &&
                        /سيتم تحميل بقية الصفحات عند المتابعة|remaining pages.*continue/i.test(element.textContent || '')) {
                        element.style.setProperty('opacity', '0', 'important');
                        element.style.setProperty('pointer-events', 'none', 'important');
                    }
                });

                if (!window.__mslimeReaderFocusScrolled) {
                    window.__mslimeReaderFocusScrolled = true;
                    window.scrollTo(0, 0);
                }
            };

            let lastContentHeight = 0;
            let lastRowCount = 0;
            let contentStableAt = Date.now();
            let reachedBottomAt = 0;

            const checkForNextChapter = function(userSwipedUp) {
                if (window.__mslimeNavigatingNext) return;
                const nextUrl = findNextChapterUrl();
                window.__mslimeNextChapterUrl = nextUrl;

                const rows = Array.from(document.querySelectorAll('[data-reader-image-row]'));
                if (rows.length === 0 || (!window.__mslimeReaderHasScrolled && !userSwipedUp)) {
                    reachedBottomAt = 0;
                    return;
                }
                collectRenderedChapterPages();
                syncRenderedChapterPages();
                const height = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
                if (height !== lastContentHeight || rows.length !== lastRowCount) {
                    lastContentHeight = height;
                    lastRowCount = rows.length;
                    contentStableAt = Date.now();
                }

                const viewport = window.innerHeight || 1;
                const distanceFromBottom = height - (window.scrollY + viewport);
                const threshold = Math.max(120, Math.min(360, viewport * 0.14));
                const fitsViewport = height <= viewport + threshold;
                if (
                    distanceFromBottom > threshold ||
                    (window.scrollY <= 1 && fitsViewport && !window.__mslimeReaderHasScrolled && !userSwipedUp)
                ) {
                    reachedBottomAt = 0;
                    return;
                }

                const morePagesPending = Array.from(document.querySelectorAll('div')).some(function(element) {
                    return element.children.length === 0 &&
                        /سيتم تحميل بقية الصفحات عند المتابعة|remaining pages.*continue/i.test(element.textContent || '');
                });
                if (morePagesPending) {
                    reachedBottomAt = 0;
                    return;
                }

                if (!reachedBottomAt) reachedBottomAt = Date.now();
                const stayedAtEnd = Date.now() - reachedBottomAt >= 1100;
                const contentIsStable = Date.now() - contentStableAt >= 900;
                if (stayedAtEnd && contentIsStable) {
                    cacheRenderedChapterPages();
                    const bridge = window.MslProComicDownloadBridge;
                    const allPages = collectRenderedChapterPages();
                    const syncedCount = window.__mslimeSyncedProComicPageCount || 0;
                    if (bridge && syncedCount < allPages.length) return;
                    if (bridge && typeof bridge.chapterPagesReady === 'function') {
                        const ready = bridge.chapterPagesReady(window.location.href);
                        if (!ready) return;
                    }
                    if (bridge && typeof bridge.isDownloadPreparation === 'function' &&
                        bridge.isDownloadPreparation()) {
                        window.__mslimeDownloadPreparationReady = true;
                        return;
                    }
                    if (nextUrl) {
                        window.__mslimeNavigatingNext = true;
                        window.location.assign(nextUrl);
                    }
                }
            };

            window.__mslimeApplyProComicReaderOnly = applyReaderOnly;
            window.__mslimeCheckProComicEnd = checkForNextChapter;
            if (!window.__mslimeProComicReaderObserver) {
                window.__mslimeProComicReaderObserver = new MutationObserver(applyReaderOnly);
                window.__mslimeProComicReaderObserver.observe(document.documentElement, {
                    childList:true,
                    subtree:true
                });
            }
            if (!window.__mslimeProComicAutoNextInstalled) {
                let touchStartY = null;
                let previousScrollY = window.scrollY;
                window.addEventListener('scroll', function() {
                    const currentScrollY = window.scrollY;
                    if (currentScrollY > previousScrollY + 1) window.__mslimeReaderHasScrolled = true;
                    previousScrollY = currentScrollY;
                    window.__mslimeCheckProComicEnd(false);
                }, {passive:true});
                window.addEventListener('resize', function() {
                    window.__mslimeCheckProComicEnd(false);
                }, {passive:true});
                window.addEventListener('touchstart', function(event) {
                    touchStartY = event.changedTouches[0].clientY;
                }, {passive:true});
                window.addEventListener('touchend', function(event) {
                    const endY = event.changedTouches[0].clientY;
                    const swipedUp = touchStartY !== null && touchStartY - endY > 48;
                    touchStartY = null;
                    if (swipedUp) {
                        window.__mslimeReaderHasScrolled = true;
                        window.__mslimeCheckProComicEnd(true);
                    }
                }, {passive:true});
                window.setInterval(function() {
                    window.__mslimeCheckProComicEnd(false);
                }, 400);
                window.__mslimeProComicAutoNextInstalled = true;
            }
            applyReaderOnly();
        })();
    """.trimIndent()
}
