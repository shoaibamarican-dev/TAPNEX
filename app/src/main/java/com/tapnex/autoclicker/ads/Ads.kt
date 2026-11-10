package com.tapnex.autoclicker.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.tapnex.autoclicker.BuildConfig

/**
 * Ad unit configuration. Debug builds use Google's official test units (set in app/build.gradle.kts).
 * Release builds use the real TAPNEX units.
 */
object AdConfig {
    val usesTestAds: Boolean get() = BuildConfig.USE_TEST_ADS
    val bannerUnitId: String get() = BuildConfig.BANNER_AD_UNIT_ID
    val rewardedUnitId: String get() = BuildConfig.REWARDED_AD_UNIT_ID

    /**
     * If true, a game opens anyway when no rewarded ad could be loaded (no internet / no fill).
     * Keep false to always require the ad.
     */
    const val UNLOCK_WHEN_AD_UNAVAILABLE = false
}

@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdConfig.bannerUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() }
    )
}

/** "Watch ad to unlock": one rewarded ad per game launch. */
object RewardedGate {
    enum class State { LOADING, READY, FAILED }

    /** Observed by Compose so the dialog button can show Loading / Watch ad / Retry. */
    var state by mutableStateOf(State.LOADING)
        private set

    private var ad: RewardedAd? = null
    private var loading = false
    private val handler = Handler(Looper.getMainLooper())

    fun preload(context: Context) {
        if (ad != null || loading) return
        loading = true
        state = State.LOADING
        RewardedAd.load(
            context.applicationContext,
            AdConfig.rewardedUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    ad = rewardedAd
                    loading = false
                    state = State.READY
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    loading = false
                    state = State.FAILED
                }
            }
        )
    }

    /** Shows the ad. [onUnlocked] runs only after the user earned the reward and closed the ad. */
    fun show(activity: Activity, onUnlocked: () -> Unit, onUnavailable: (String) -> Unit) {
        val loaded = ad
        if (loaded == null) {
            preload(activity)
            if (AdConfig.UNLOCK_WHEN_AD_UNAVAILABLE && state == State.FAILED) onUnlocked()
            else onUnavailable("Ad is not ready yet. Check internet and try again in a moment.")
            return
        }
        var rewarded = false
        loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                state = State.LOADING
                handler.post { preload(activity) }
                if (rewarded) onUnlocked() else onUnavailable("Watch the full ad to unlock.")
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                ad = null
                state = State.LOADING
                handler.post { preload(activity) }
                onUnavailable("Ad could not be shown. Try again.")
            }
        }
        loaded.show(activity) { rewarded = true }
    }
}
