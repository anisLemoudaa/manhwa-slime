package eu.kanade.tachiyomi.ui.webview

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import android.webkit.JavascriptInterface
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import eu.kanade.presentation.webview.ProComicAdPolicy
import eu.kanade.presentation.webview.WebViewScreenContent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.mslime.ProComicSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

private data class ProComicPreparationTarget(
    val chapterId: Long,
    val url: String,
    val path: String,
)

private class ProComicReaderDownloadBridge(
    private val source: ProComicSource,
    private val targets: List<ProComicPreparationTarget>,
    private val onChapterReady: (Long, Boolean) -> Unit,
) {
    private var currentIndex = 0
    private var currentChapterReady = false

    @JavascriptInterface
    fun cacheChapterPages(chapterUrl: String, serializedPageUrls: String) {
        if (serializedPageUrls.length > 1_000_000) return
        val pageUrls = runCatching {
            val array = JSONArray(serializedPageUrls)
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }.getOrDefault(emptyList())
        source.cacheReaderPageUrls(chapterUrl, pageUrls)
    }

    @JavascriptInterface
    fun isDownloadPreparation(): Boolean = targets.isNotEmpty() && currentIndex < targets.size

    @JavascriptInterface
    @Synchronized
    fun chapterPagesReady(chapterUrl: String): Boolean {
        val target = targets.getOrNull(currentIndex) ?: return false
        val currentPath = runCatching { URI(chapterUrl).path }.getOrNull()
        if (currentPath != target.path || !source.markReaderPageListComplete(chapterUrl)) return false
        if (!currentChapterReady) {
            currentChapterReady = true
            onChapterReady(target.chapterId, currentIndex == targets.lastIndex)
        }
        return true
    }

    @JavascriptInterface
    @Synchronized
    fun nextDownloadChapterUrl(chapterUrl: String): String? {
        if (!currentChapterReady) return null
        val currentPath = runCatching { URI(chapterUrl).path }.getOrNull()
        if (currentPath != targets.getOrNull(currentIndex)?.path) return null
        val nextIndex = currentIndex + 1
        val next = targets.getOrNull(nextIndex) ?: return null
        currentIndex = nextIndex
        currentChapterReady = false
        return next.url
    }
}

@HasMemberInjections
open class WebViewActivity : BaseActivity() {

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var network: NetworkHelper

    @Inject private lateinit var downloadManager: DownloadManager

    private var assistUrl: String? = null
    private var immersiveReaderMode = false
    private var downloadPreparationMode = false
    private val preparedDownloadStarted = AtomicBoolean(false)
    private val preparedChapterEvents = Channel<Pair<Long, Boolean>>(Channel.UNLIMITED)
    private var proComicDownloadBridge: ProComicReaderDownloadBridge? = null
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val adFreeReader = intent.getBooleanExtra(AD_FREE_READER_KEY, false)
        val chapterIds = intent.getLongArrayExtra(PREPARE_DOWNLOAD_CHAPTER_IDS_KEY)
            ?.filter { it > 0 }
            .orEmpty()
        val chapterUrls = intent.getStringArrayListExtra(PREPARE_DOWNLOAD_CHAPTER_URLS_KEY).orEmpty()
        val preparationTargets = chapterIds.zip(chapterUrls).mapNotNull { (id, targetUrl) ->
            val path = runCatching { URI(targetUrl).path }.getOrNull()
            if (id > 0 && ProComicAdPolicy.isAllowedMainFrame(targetUrl) && path?.startsWith("/ar/chapter/") == true) {
                ProComicPreparationTarget(id, targetUrl, path)
            } else {
                null
            }
        }
        downloadPreparationMode = preparationTargets.isNotEmpty() && preparationTargets.size == chapterIds.size &&
            chapterUrls.size == chapterIds.size
        if (adFreeReader && !downloadPreparationMode) enableEdgeToEdge()
        if (downloadPreparationMode) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }
        super.onCreate(savedInstanceState)
        appGraph.inject(this)
        immersiveReaderMode = adFreeReader && !downloadPreparationMode
        if (downloadPreparationMode) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val url = intent.extras?.getString(URL_KEY) ?: return
        if (adFreeReader && !ProComicAdPolicy.isAllowedMainFrame(url)) {
            finish()
            return
        }
        if (intent.hasExtra(PREPARE_DOWNLOAD_CHAPTER_IDS_KEY) &&
            (!downloadPreparationMode || runCatching { URI(url).path }.getOrNull() != preparationTargets.firstOrNull()?.path)
        ) {
            finish()
            return
        }
        if (immersiveReaderMode) applyImmersiveReaderMode()
        assistUrl = url
        val prepareStartNow = intent.getBooleanExtra(PREPARE_START_NOW_KEY, false)
        if (downloadPreparationMode) {
            val timeoutMs = (DOWNLOAD_PREPARATION_TIMEOUT_BASE_MS +
                (preparationTargets.size - 1) * DOWNLOAD_PREPARATION_TIMEOUT_PER_CHAPTER_MS)
                .coerceAtMost(DOWNLOAD_PREPARATION_TIMEOUT_MAX_MS)
            lifecycleScope.launch {
                delay(timeoutMs)
                if (!preparedDownloadStarted.get() && !isFinishing) {
                    toast(MR.strings.procomic_download_prepare_failed, Toast.LENGTH_LONG)
                    finish()
                }
            }
            lifecycleScope.launch(Dispatchers.IO) {
                var firstChapter = true
                for ((chapterId, isLastChapter) in preparedChapterEvents) {
                    val queued = runCatching {
                        downloadManager.downloadChapterIds(
                            listOf(chapterId),
                            startNow = prepareStartNow && firstChapter,
                        )
                    }.getOrDefault(false)
                    if (!queued) {
                        preparedDownloadStarted.set(true)
                        withContext(Dispatchers.Main) {
                            toast(MR.strings.procomic_download_prepare_failed, Toast.LENGTH_LONG)
                            finish()
                        }
                        return@launch
                    }
                    firstChapter = false
                    if (isLastChapter && preparedDownloadStarted.compareAndSet(false, true)) {
                        withContext(Dispatchers.Main) {
                            toast(MR.strings.procomic_download_started, Toast.LENGTH_LONG)
                            finish()
                        }
                        return@launch
                    }
                }
            }
        }

        setComposeContent {
            // Null until the source it belongs to has been resolved
            val headers by produceState<Map<String, String>?>(initialValue = null) {
                val source = sourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? HttpSource
                if (adFreeReader) {
                    proComicDownloadBridge = (source as? ProComicSource)?.let {
                        ProComicReaderDownloadBridge(
                            source = it,
                            targets = if (downloadPreparationMode) preparationTargets else emptyList(),
                            onChapterReady = { chapterId, isLastChapter ->
                                preparedChapterEvents.trySend(chapterId to isLastChapter)
                            },
                        )
                    }
                }
                value = try {
                    source?.headers?.toMultimap()?.mapValues { it.value.getOrNull(0) ?: "" }.orEmpty()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to build headers" }
                    emptyMap()
                }
            }

            if (headers == null) {
                if (!downloadPreparationMode) LoadingScreen()
                return@setComposeContent
            }

            WebViewScreenContent(
                onNavigateUp = { finish() },
                initialTitle = intent.extras?.getString(TITLE_KEY),
                url = url,
                adFreeReader = adFreeReader,
                downloadPreparation = downloadPreparationMode,
                headers = headers.orEmpty(),
                defaultUserAgentProvider = network::defaultUserAgentProvider,
                onUrlChange = { assistUrl = it },
                onWebViewCreated = { webView ->
                    if (adFreeReader) {
                        proComicDownloadBridge?.let {
                            webView.addJavascriptInterface(it, "MslProComicDownloadBridge")
                        }
                    }
                },
                onShare = this::shareWebpage,
                onOpenInBrowser = this::openInBrowser,
                onClearCookies = this::clearCookies,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (immersiveReaderMode) applyImmersiveReaderMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveReaderMode) applyImmersiveReaderMode()
    }

    private fun applyImmersiveReaderMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    override fun finish() {
        super.finish()
        if (downloadPreparationMode) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    private fun shareWebpage(url: String) {
        try {
            startActivity(url.toUri().toShareIntent(this, type = "text/plain"))
        } catch (e: Exception) {
            toast(e.message)
        }
    }

    private fun openInBrowser(url: String) {
        openInBrowser(url, forceDefaultBrowser = true)
    }

    private fun clearCookies(url: String) {
        val cleared = network.cookieJar.remove(url.toHttpUrl())
        logcat { "Cleared $cleared cookies for: $url" }
    }

    companion object {
        private const val URL_KEY = "url_key"
        private const val SOURCE_KEY = "source_key"
        private const val TITLE_KEY = "title_key"
        private const val AD_FREE_READER_KEY = "ad_free_reader_key"
        private const val PREPARE_DOWNLOAD_CHAPTER_IDS_KEY = "prepare_download_chapter_ids_key"
        private const val PREPARE_DOWNLOAD_CHAPTER_URLS_KEY = "prepare_download_chapter_urls_key"
        private const val PREPARE_START_NOW_KEY = "prepare_start_now_key"
        private const val DOWNLOAD_PREPARATION_TIMEOUT_BASE_MS = 10 * 60 * 1000L
        private const val DOWNLOAD_PREPARATION_TIMEOUT_PER_CHAPTER_MS = 2 * 60 * 1000L
        private const val DOWNLOAD_PREPARATION_TIMEOUT_MAX_MS = 12 * 60 * 60 * 1000L

        fun newIntent(
            context: Context,
            url: String,
            sourceId: Long? = null,
            title: String? = null,
            adFreeReader: Boolean = false,
            prepareDownloadChapterId: Long? = null,
            prepareDownloadChapterIds: LongArray? = null,
            prepareDownloadChapterUrls: ArrayList<String>? = null,
            prepareStartNow: Boolean = false,
        ): Intent {
            val ids = prepareDownloadChapterIds ?: prepareDownloadChapterId?.let { longArrayOf(it) }
            val urls = prepareDownloadChapterUrls
                ?: if (prepareDownloadChapterId != null) arrayListOf(url) else null
            val isPreparation = adFreeReader && ids != null && urls != null
            return Intent(
                context,
                if (isPreparation) ProComicDownloadPreparationActivity::class.java else WebViewActivity::class.java,
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(URL_KEY, url)
                putExtra(SOURCE_KEY, sourceId)
                putExtra(TITLE_KEY, title)
                putExtra(AD_FREE_READER_KEY, adFreeReader)
                if (ids != null && urls != null) {
                    putExtra(PREPARE_DOWNLOAD_CHAPTER_IDS_KEY, ids)
                    putStringArrayListExtra(PREPARE_DOWNLOAD_CHAPTER_URLS_KEY, urls)
                    putExtra(PREPARE_START_NOW_KEY, prepareStartNow)
                }
            }
        }
    }
}

class ProComicDownloadPreparationActivity : WebViewActivity() {
    override val appThemeResource: Int
        get() = R.style.Theme_Tachiyomi_ProComicPreparation
}
