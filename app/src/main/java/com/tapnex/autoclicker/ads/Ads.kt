package com.tapnex.autoclicker.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.tapnex.autoclicker.BuildConfig

/** Ad unit configuration. Debug builds use Google's official test banner unit. */
object AdConfig {
    const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    val usesTestAds: Boolean get() = BuildConfig.USE_TEST_ADS
    val bannerUnitId: String get() = if (usesTestAds) TEST_BANNER else BuildConfig.BANNER_AD_UNIT_ID
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

