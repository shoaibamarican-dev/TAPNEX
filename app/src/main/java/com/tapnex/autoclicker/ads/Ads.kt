package com.tapnex.autoclicker.ads

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.tapnex.autoclicker.BuildConfig
import com.tapnex.autoclicker.account.WalletService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ad unit configuration.
 * Debug and default release builds use Google's TEST units, which never grant credits.
 * A production build sets `tapnexProductionAds=true` plus the unit IDs in gradle.properties or
 * environment variables (see build.gradle.kts). Credits are granted only by the server (SSV).
 */
object AdConfig {
    const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val REWARD_CREDITS = 5L

    val usesTestAds: Boolean get() = BuildConfig.USE_TEST_ADS
    val bannerUnitId: String get() = if (usesTestAds) TEST_BANNER else BuildConfig.BANNER_AD_UNIT_ID
    val rewardedUnitId: String get() = if (usesTestAds) TEST_REWARDED else BuildConfig.REWARDED_AD_UNIT_ID
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

/**
 * Shows a rewarded ad, then waits for the server to credit the wallet.
 * The client never adds credits itself. A closed ad or a client callback is not a reward.
 */
@Composable
fun RewardedAdButton(
    label: String,
    userId: String?,
    onStatus: (String) -> Unit,
    onWalletUpdated: (balance: Long) -> Unit,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OutlinedButton(
        enabled = enabled,
        onClick = { startRewardedAd(context as? Activity, userId, scope, onStatus, onWalletUpdated) },
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

private fun startRewardedAd(
    activity: Activity?,
    userId: String?,
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
    onWalletUpdated: (Long) -> Unit
) {
    if (activity == null) {
        onStatus("Ads cannot be shown from this screen.")
        return
    }
    if (userId == null) {
        onStatus("Sign in before watching an ad to earn credits.")
        return
    }
    onStatus("Loading ad...")
    // Read the wallet BEFORE the ad starts. If the server callback arrives before the ad closes,
    // a baseline read afterwards would already include the reward and the credit would be missed.
    scope.launch {
        val baseline = try {
            WalletService.load(userId).totalEarned
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        loadAndShowRewardedAd(activity, userId, baseline, scope, onStatus, onWalletUpdated)
    }
}

private fun loadAndShowRewardedAd(
    activity: Activity,
    userId: String,
    baseline: Long?,
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
    onWalletUpdated: (Long) -> Unit
) {
    RewardedAd.load(
        activity,
        AdConfig.rewardedUnitId,
        AdRequest.Builder().build(),
        object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                if (!AdConfig.usesTestAds) {
                    // SSV: AdMob calls our server with this user id; the server credits that user.
                    ad.setServerSideVerificationOptions(
                        ServerSideVerificationOptions.Builder().setUserId(userId).build()
                    )
                }
                var earned = false
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        if (!earned) {
                            onStatus("Ad closed before completion. No credits were added.")
                        } else if (AdConfig.usesTestAds) {
                            onStatus("Test ad completed. No credits were added because this is a test ad.")
                        } else {
                            onStatus("Verifying your reward...")
                            scope.launch { awaitServerCredit(userId, baseline, onStatus, onWalletUpdated) }
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        onStatus("Ad could not be shown (code ${error.code}). Try again later.")
                    }
                }
                ad.show(activity) { earned = true }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                onStatus("Ad failed to load (code ${error.code}). Check your internet connection and try again.")
            }
        }
    )
}

/** Polls the wallet until totalEarned rises above the pre-ad baseline, or gives up after ~30 s. */
private suspend fun awaitServerCredit(
    uid: String,
    before: Long?,
    onStatus: (String) -> Unit,
    onWalletUpdated: (Long) -> Unit
) {
    if (before == null) {
        onStatus("Could not check your wallet. Use Refresh wallet on the Account screen to see if credits arrived.")
        return
    }
    repeat(15) {
        delay(2_000)
        try {
            val snapshot = WalletService.load(uid)
            if (snapshot.totalEarned > before) {
                onWalletUpdated(snapshot.balance)
                onStatus("${AdConfig.REWARD_CREDITS} credits added to your wallet.")
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network hiccup: keep polling.
        }
    }
    onStatus("Your reward is still being verified. It will appear in your wallet shortly. Use Refresh wallet to check.")
}
