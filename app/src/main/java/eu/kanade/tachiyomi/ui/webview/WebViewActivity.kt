package eu.kanade.tachiyomi.ui.webview

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zacsweers.metro.Inject
import eu.kanade.presentation.webview.ProComicAdPolicy
import eu.kanade.presentation.webview.WebViewScreenContent
import eu.kanade.tachiyomi.R
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
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

class WebViewActivity : BaseActivity() {

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var network: NetworkHelper

    private var assistUrl: String? = null
    private var immersiveReaderMode = false
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

        setComposeContent {
            // Null until the source it belongs to has been resolved
            val headers by produceState<Map<String, String>?>(initialValue = null) {
                val source = sourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? HttpSource
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

        fun newIntent(
            context: Context,
            url: String,
            sourceId: Long? = null,
            title: String? = null,
            adFreeReader: Boolean = false,
        ): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(URL_KEY, url)
                putExtra(SOURCE_KEY, sourceId)
                putExtra(TITLE_KEY, title)
                putExtra(AD_FREE_READER_KEY, adFreeReader)
            }
        }
    }
}
