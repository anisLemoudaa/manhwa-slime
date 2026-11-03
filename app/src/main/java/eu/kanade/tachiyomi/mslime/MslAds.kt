package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsLoadOptions
import com.unity3d.ads.UnityAds.UnityAdsShowCompletionState
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.ads.metadata.PlayerMetaData
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Unity Ads integration. Rewarded coins are granted only by the signed Unity S2S callback. */
object MslAds {
    private const val PREFS = "msl_ads"
    private const val PRIVACY_PREFS = "unity_ads_privacy"
    private const val PERSONALIZED_ADS_CONSENT = "personalized_ads_consent"
    private const val LOAD_RETRY_DELAY_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val consentFlowStarted = AtomicBoolean(false)
    private val consentPromptInProgress = AtomicBoolean(false)
    private val sdkInitialized = AtomicBoolean(false)
    private val rewardedReady = AtomicBoolean(false)
    private val rewardedLoading = AtomicBoolean(false)
    private val interstitialReady = AtomicBoolean(false)
    private val interstitialLoading = AtomicBoolean(false)
    private val vipInterstitialCheckInProgress = AtomicBoolean(false)

    var privacyOptionsAvailable by mutableStateOf(false)
        private set

    private fun gameId(): String = BuildConfig.UNITY_ADS_GAME_ID.trim()
    private fun rewardedUnitId(): String = BuildConfig.UNITY_ADS_REWARDED_AD_UNIT_ID.trim()
    private fun interstitialUnitId(): String = BuildConfig.UNITY_ADS_INTERSTITIAL_AD_UNIT_ID.trim()

    /** Call from the launcher activity after it has become the task root. */
    fun requestConsentAndInitialize(activity: Activity) {
        if (!consentFlowStarted.compareAndSet(false, true)) return
        if (gameId().isBlank() || (rewardedUnitId().isBlank() && interstitialUnitId().isBlank())) return

        privacyOptionsAvailable = true
        val preferences = activity.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(PERSONALIZED_ADS_CONSENT)) {
            applyPrivacyChoice(
                activity.applicationContext,
                preferences.getBoolean(PERSONALIZED_ADS_CONSENT, false),
            )
        } else {
            showConsentDialog(activity, initialPrompt = true)
        }
    }

    /** Presents the in-app consent choice used to update Unity's Developer Consent API. */
    fun showPrivacyOptions(activity: Activity) {
        showConsentDialog(activity, initialPrompt = false)
    }

    private fun showConsentDialog(activity: Activity, initialPrompt: Boolean) {
        if (!consentPromptInProgress.compareAndSet(false, true)) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                consentPromptInProgress.set(false)
                return@runOnUiThread
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("خيارات خصوصية الإعلانات")
                .setMessage(
                    "يمكن أن تستخدم Unity Ads معرّف الإعلانات ومعلومات الجهاز لعرض إعلانات مخصّصة وقياس أدائها. " +
                        "إذا اخترت عدم الموافقة، سيُطلب عرض إعلانات غير مخصّصة عند توفرها. " +
                        "يمكنك تغيير اختيارك لاحقًا من إعدادات الخصوصية داخل التطبيق.",
                )
                .setPositiveButton("السماح بالإعلانات المخصّصة") { _, _ ->
                    applyPrivacyChoice(activity.applicationContext, personalizedAdsAllowed = true)
                }
                .setNegativeButton("إعلانات غير مخصّصة") { _, _ ->
                    applyPrivacyChoice(activity.applicationContext, personalizedAdsAllowed = false)
                }
                .setCancelable(!initialPrompt)
                .create()
            dialog.setOnDismissListener { consentPromptInProgress.set(false) }
            dialog.show()
        }
    }

    private fun applyPrivacyChoice(context: Context, personalizedAdsAllowed: Boolean) {
        context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PERSONALIZED_ADS_CONSENT, personalizedAdsAllowed)
            .apply()

        // Set the consent signals before first SDK initialization; a decline still permits contextual ads.
        UnityAds.userConsent = personalizedAdsAllowed
        UnityAds.userOptOut = !personalizedAdsAllowed
        if (!sdkInitialized.get()) {
            initializeAndPreload(context.applicationContext)
        } else {
            rewardedReady.set(false)
            interstitialReady.set(false)
            preloadRewardedAd()
            preloadInterstitialAd()
        }
    }

    private fun initializeAndPreload(context: Context) {
        if (!sdkInitialized.compareAndSet(false, true)) return
        UnityAds.initialize(
            context,
            gameId(),
            BuildConfig.DEBUG,
            object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    Log.i("MslAds", "Unity Ads initialized")
                    preloadRewardedAd()
                    preloadInterstitialAd()
                }

                override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError, message: String) {
                    Log.e("MslAds", "Unity Ads initialization failed: $error $message")
                    sdkInitialized.set(false)
                }
            },
        )
    }

    private fun preloadRewardedAd() {
        val unitId = rewardedUnitId()
        if (!sdkInitialized.get() || unitId.isBlank() || !rewardedLoading.compareAndSet(false, true)) return
        UnityAds.load(
            unitId,
            UnityAdsLoadOptions(),
            object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String) {
                    rewardedLoading.set(false)
                    rewardedReady.set(true)
                    Log.i("MslAds", "Unity rewarded ad ready: $placementId")
                }

                override fun onUnityAdsFailedToLoad(
                    placementId: String,
                    error: UnityAds.UnityAdsLoadError,
                    message: String,
                ) {
                    rewardedLoading.set(false)
                    rewardedReady.set(false)
                    Log.w("MslAds", "Unity rewarded load failed: $placementId $error $message")
                    mainHandler.postDelayed({ preloadRewardedAd() }, LOAD_RETRY_DELAY_MS)
                }
            },
        )
    }

    private fun preloadInterstitialAd() {
        val unitId = interstitialUnitId()
        if (!sdkInitialized.get() || unitId.isBlank() || !interstitialLoading.compareAndSet(false, true)) return
        UnityAds.load(
            unitId,
            UnityAdsLoadOptions(),
            object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String) {
                    interstitialLoading.set(false)
                    interstitialReady.set(true)
                    Log.i("MslAds", "Unity interstitial ad ready: $placementId")
                }

                override fun onUnityAdsFailedToLoad(
                    placementId: String,
                    error: UnityAds.UnityAdsLoadError,
                    message: String,
                ) {
                    interstitialLoading.set(false)
                    interstitialReady.set(false)
                    Log.w("MslAds", "Unity interstitial load failed: $placementId $error $message")
                    mainHandler.postDelayed({ preloadInterstitialAd() }, LOAD_RETRY_DELAY_MS)
                }
            },
        )
    }

    /** Returns false if the wallet, signed reward flow, SDK, or preloaded ad is unavailable. */
    fun showRewarded(
        activity: Activity,
        sessionId: String,
        onRewardEarned: () -> Unit,
        onAdClosed: () -> Unit,
        onAdUnavailable: () -> Unit,
    ): Boolean {
        if (MslVip.cachedActive(activity)) {
            onAdUnavailable()
            return false
        }
        if (!BuildConfig.COIN_WALLET_ENABLED || (!BuildConfig.DEBUG && !BuildConfig.UNITY_ADS_S2S_ENABLED) || !sdkInitialized.get()) {
            onAdUnavailable()
            return false
        }
        val unitId = rewardedUnitId()
        if (unitId.isBlank()) {
            onAdUnavailable()
            return false
        }
        if (!rewardedReady.compareAndSet(true, false)) {
            preloadRewardedAd()
            onAdUnavailable()
            return false
        }

        runCatching {
            PlayerMetaData(activity.applicationContext).apply {
                setServerId(sessionId)
                commit()
            }
        }.onFailure {
            Log.e("MslAds", "Could not attach Unity S2S reward session", it)
            onAdUnavailable()
            preloadRewardedAd()
            return false
        }

        val callbackHandled = AtomicBoolean(false)
        UnityAds.show(
            activity,
            unitId,
            UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String,
                ) {
                    Log.e("MslAds", "Unity rewarded show failed: $placementId $error $message")
                    if (callbackHandled.compareAndSet(false, true)) onAdClosed()
                    preloadRewardedAd()
                }

                override fun onUnityAdsShowStart(placementId: String) = Unit

                override fun onUnityAdsShowClick(placementId: String) = Unit

                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAdsShowCompletionState,
                ) {
                    if (!callbackHandled.compareAndSet(false, true)) return
                    if (state == UnityAdsShowCompletionState.COMPLETED) {
                        // This only starts client-side status polling. The signed S2S callback grants coins.
                        onRewardEarned()
                    } else {
                        onAdClosed()
                    }
                    preloadRewardedAd()
                }
            },
        )
        return true
    }

    /** Wait briefly for Unity's asynchronous load callback before reporting no fill. */
    suspend fun showRewardedWhenReady(
        activity: Activity,
        sessionId: String,
        onRewardEarned: () -> Unit,
        onAdClosed: () -> Unit,
        onAdUnavailable: () -> Unit,
    ): Boolean {
        if (!BuildConfig.COIN_WALLET_ENABLED || (!BuildConfig.DEBUG && !BuildConfig.UNITY_ADS_S2S_ENABLED) || !sdkInitialized.get()) {
            onAdUnavailable()
            return false
        }
        preloadRewardedAd()
        repeat(20) { attempt ->
            if (showRewarded(activity, sessionId, onRewardEarned, onAdClosed) {}) return true
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
                if (!interstitialReady.compareAndSet(true, false)) {
                    preloadInterstitialAd()
                    return@runOnUiThread
                }
                // Consume the cadence before showing, so a failed presentation cannot repeat on every swipe.
                preferences.edit().putBoolean("interstitial_pending", false).apply()
                UnityAds.show(
                    activity,
                    unitId,
                    UnityAdsShowOptions(),
                    object : IUnityAdsShowListener {
                        override fun onUnityAdsShowFailure(
                            placementId: String,
                            error: UnityAds.UnityAdsShowError,
                            message: String,
                        ) {
                            Log.w("MslAds", "Unity interstitial show failed: $placementId $error $message")
                            preloadInterstitialAd()
                        }

                        override fun onUnityAdsShowStart(placementId: String) = Unit
                        override fun onUnityAdsShowClick(placementId: String) = Unit
                        override fun onUnityAdsShowComplete(placementId: String, state: UnityAdsShowCompletionState) {
                            preloadInterstitialAd()
                        }
                    },
                )
            }
        }.start()
    }
}
