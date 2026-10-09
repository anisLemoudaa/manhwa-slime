package eu.kanade.tachiyomi.ui.webview

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.webkit.JavascriptInterface
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

private class ProComicReaderDownloadBridge(
    private val source: ProComicSource,
    private val prepareDownloadChapterId: Long?,
    private val prepareDownloadChapterPath: String?,
    private val onChapterReady: (Long) -> Unit,
) {
    private val downloadStarted = AtomicBoolean(false)

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
    fun isDownloadPreparation(): Boolean = prepareDownloadChapterId != null

    @JavascriptInterface
    fun chapterPagesReady(chapterUrl: String): Boolean {
        if (!source.markReaderPageListComplete(chapterUrl)) return false
        val targetId = prepareDownloadChapterId ?: return true
        val currentPath = runCatching { URI(chapterUrl).path }.getOrNull()
        if (currentPath != prepareDownloadChapterPath) return false
        if (downloadStarted.compareAndSet(false, true)) onChapterReady(targetId)
        return true
    }
}

class WebViewActivity : BaseActivity() {

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var network: NetworkHelper

    @Inject private lateinit var downloadManager: DownloadManager

    private var assistUrl: String? = null
    private var immersiveReaderMode = false
    private var proComicDownloadBridge: ProComicReaderDownloadBridge? = null
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val adFreeReader = intent.getBooleanExtra(AD_FREE_READER_KEY, false)
        if (adFreeReader) enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
        immersiveReaderMode = adFreeReader

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
        if (adFreeReader) applyImmersiveReaderMode()
        assistUrl = url
        val prepareDownloadChapterId = intent.extras?.getLong(PREPARE_DOWNLOAD_CHAPTER_ID_KEY)
            ?.takeIf { it > 0 }
        val prepareDownloadChapterPath = runCatching { URI(url).path }.getOrNull()
        if (prepareDownloadChapterId != null) {
            toast(MR.strings.procomic_download_scroll_hint, Toast.LENGTH_LONG)
        }

        setComposeContent {
            // Null until the source it belongs to has been resolved
            val headers by produceState<Map<String, String>?>(initialValue = null) {
                val source = sourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? HttpSource
                if (adFreeReader) {
                    proComicDownloadBridge = (source as? ProComicSource)?.let {
                        ProComicReaderDownloadBridge(
                            source = it,
                            prepareDownloadChapterId = prepareDownloadChapterId,
                            prepareDownloadChapterPath = prepareDownloadChapterPath,
                            onChapterReady = { chapterId ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    downloadManager.startDownloadNow(chapterId)
                                    withContext(Dispatchers.Main) {
                                        toast(MR.strings.procomic_download_started, Toast.LENGTH_LONG)
                                    }
                                }
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
                LoadingScreen()
                return@setComposeContent
            }

            WebViewScreenContent(
                onNavigateUp = { finish() },
                initialTitle = intent.extras?.getString(TITLE_KEY),
                url = url,
                adFreeReader = adFreeReader,
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
        private const val PREPARE_DOWNLOAD_CHAPTER_ID_KEY = "prepare_download_chapter_id_key"

        fun newIntent(
            context: Context,
            url: String,
            sourceId: Long? = null,
            title: String? = null,
            adFreeReader: Boolean = false,
            prepareDownloadChapterId: Long? = null,
        ): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(URL_KEY, url)
                putExtra(SOURCE_KEY, sourceId)
                putExtra(TITLE_KEY, title)
                putExtra(AD_FREE_READER_KEY, adFreeReader)
                if (prepareDownloadChapterId != null) {
                    putExtra(PREPARE_DOWNLOAD_CHAPTER_ID_KEY, prepareDownloadChapterId)
                }
            }
        }
    }
}
