package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Consent-gated Google Mobile Ads Next-Gen integration. */
object MslAds {
    private const val TEST_REWARDED_UNIT = "ca-app-pub-3940256099942544/5224354917"
    private const val TEST_INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"
    private const val PREFS = "msl_ads"

    private val consentFlowStarted = AtomicBoolean(false)
    private val sdkInitialized = AtomicBoolean(false)
    private val vipInterstitialCheckInProgress = AtomicBoolean(false)
    private var consentInformation: ConsentInformation? = null

    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private fun rewardedUnitId(): String =
        if (BuildConfig.DEBUG) TEST_REWARDED_UNIT else BuildConfig.ADMOB_REWARDED_AD_UNIT_ID

    private fun interstitialUnitId(): String =
        BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID.ifBlank { if (BuildConfig.DEBUG) TEST_INTERSTITIAL_UNIT else "" }

    private fun appId(): String =
        if (BuildConfig.DEBUG || BuildConfig.ADMOB_APP_ID_CONFIGURED) BuildConfig.ADMOB_APP_ID else ""

    /** Call once from the launcher activity after it has become the task root. */
    fun requestConsentAndInitialize(activity: Activity) {
        if (!consentFlowStarted.compareAndSet(false, true)) return
        if (appId().isBlank() || (rewardedUnitId().isBlank() && interstitialUnitId().isBlank())) return

        val information = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = information
        val parameters = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    refreshConsentState(activity, information)
                }
            },
            {
                // UMP may still hold a valid previous decision; only request ads if it says so.
                refreshConsentState(activity, information)
            },
        )
    }

    private fun refreshConsentState(context: Context, information: ConsentInformation) {
        privacyOptionsRequired = information.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (information.canRequestAds()) initializeAndPreload(context.applicationContext)
    }

    private fun initializeAndPreload(context: Context) {
        if (!sdkInitialized.compareAndSet(false, true)) return
        Thread {
            try {
                val config = InitializationConfig.Builder(appId()).build()
                MobileAds.initialize(context, config) {
                    rewardedUnitId().takeIf(String::isNotBlank)?.let { unitId ->
                        val request = AdRequest.Builder(unitId).build()
                        RewardedAdPreloader.start(unitId, PreloadConfiguration(request))
                    }
                    interstitialUnitId().takeIf(String::isNotBlank)?.let { unitId ->
                        val request = AdRequest.Builder(unitId).build()
                        InterstitialAdPreloader.start(unitId, PreloadConfiguration(request))
                    }
                }
            } catch (_: Throwable) {
                sdkInitialized.set(false)
            }
        }.start()
    }

    fun showPrivacyOptions(activity: Activity) {
        val information = consentInformation ?: UserMessagingPlatform.getConsentInformation(activity)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            privacyOptionsRequired = information.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            if (information.canRequestAds()) initializeAndPreload(activity.applicationContext)
        }
    }

    /** Returns false if no ad has been preloaded or the SDK is not consent-ready. */
    fun showRewarded(
        activity: Activity,
        userId: String,
        sessionId: String,
        onRewardEarned: () -> Unit,
        onAdClosed: () -> Unit,
        onAdUnavailable: () -> Unit,
    ): Boolean {
        if (MslVip.cachedActive(activity)) {
            onAdUnavailable()
            return false
        }
        if (!BuildConfig.COIN_WALLET_ENABLED || !sdkInitialized.get()) {
            onAdUnavailable()
            return false
        }
        val unitId = rewardedUnitId()
        if (unitId.isBlank()) {
            onAdUnavailable()
            return false
        }
        val ad = RewardedAdPreloader.pollAd(unitId)
        if (ad == null) {
            onAdUnavailable()
            return false
        }
        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions(userId, sessionId),
        )
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError) {
                onAdClosed()
            }
        }
        ad.show(activity) { onRewardEarned() }
        return true
    }

    /**
     * Waits briefly for the asynchronous preloader instead of treating a still-loading ad as no-fill.
     * The server session is created before this call, but coins are still granted only by SSV.
     */
    suspend fun showRewardedWhenReady(
        activity: Activity,
        userId: String,
        sessionId: String,
        onRewardEarned: () -> Unit,
        onAdClosed: () -> Unit,
        onAdUnavailable: () -> Unit,
    ): Boolean {
        repeat(20) { attempt ->
            if (showRewarded(activity, userId, sessionId, onRewardEarned, onAdClosed) {
                    // The ad is still loading; keep waiting until the bounded retry window ends.
                }) {
                return true
            }
            if (attempt < 19) delay(500)
        }
        onAdUnavailable()
        return false
    }

    /** Records completed online chapters. It never triggers an ad while the user is reading a page. */
    fun recordOnlineChapterCompleted(context: Context, chapterKey: String) {
        if (interstitialUnitId().isBlank()) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (MslVip.cachedActive(context)) {
            preferences.edit()
                .putInt("online_chapters_since_interstitial", 0)
                .putBoolean("interstitial_pending", false)
                .apply()
            return
        }
        val chapterDigest = MessageDigest.getInstance("SHA-256")
            .digest(chapterKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val counted = preferences.getStringSet("counted_chapters_v1", emptySet()).orEmpty()
        if (chapterDigest in counted) return
        val updated = counted + chapterDigest

        val nextCount = preferences.getInt("online_chapters_since_interstitial", 0) + 1
        preferences.edit()
            .putStringSet("counted_chapters_v1", updated)
            .putInt("online_chapters_since_interstitial", if (nextCount >= BuildConfig.INTERSTITIAL_CHAPTER_INTERVAL) 0 else nextCount)
            .putBoolean("interstitial_pending", preferences.getBoolean("interstitial_pending", false) || nextCount >= BuildConfig.INTERSTITIAL_CHAPTER_INTERVAL)
            .apply()
    }

    /** Called only when a next-chapter transition page is selected, a natural break in reading. */
    fun showPendingInterstitialAtChapterBreak(activity: Activity) {
        if (!sdkInitialized.get()) return
        val preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean("interstitial_pending", false)) return
        val unitId = interstitialUnitId()
        if (unitId.isBlank()) return
        if (!vipInterstitialCheckInProgress.compareAndSet(false, true)) return
        Thread {
            val vipActive = runCatching {
                MslVip.refresh(activity.applicationContext)?.active ?: MslVip.cachedActive(activity.applicationContext)
            }.getOrDefault(false)
            activity.runOnUiThread {
                vipInterstitialCheckInProgress.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (vipActive) {
                    preferences.edit()
                        .putInt("online_chapters_since_interstitial", 0)
                        .putBoolean("interstitial_pending", false)
                        .apply()
                    return@runOnUiThread
                }
                val ad = InterstitialAdPreloader.pollAd(unitId) ?: return@runOnUiThread
                // Consume the cadence before showing, so a failed presentation cannot repeat on every swipe.
                preferences.edit().putBoolean("interstitial_pending", false).apply()
                ad.adEventCallback = object : InterstitialAdEventCallback {}
                ad.show(activity)
            }
        }.start()
    }
}
