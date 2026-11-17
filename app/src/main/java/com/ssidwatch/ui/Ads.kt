package com.ssidwatch.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.ssidwatch.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google AdMob banner support. Asks for consent first where the law requires it (UK/EEA),
 * then starts the ads SDK. Uses Google's test IDs unless real ones are supplied at build time.
 */
object Ads {
    var ready by mutableStateOf(false)
        private set
    var privacyOptionsRequired by mutableStateOf(false)
        private set
    private val started = AtomicBoolean(false)

    fun setup(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    update(activity, info)
                }
            },
            { _ -> update(activity, info) }
        )
        // consent from a previous session lets ads start straight away
        update(activity, info)
    }

    private fun update(activity: Activity, info: ConsentInformation) {
        privacyOptionsRequired =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds() && started.compareAndSet(false, true)) {
            MobileAds.initialize(activity.applicationContext) { ready = true }
        }
    }

    /** Lets the user change their ad consent choices (shown in Settings when required). */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            update(activity, UserMessagingPlatform.getConsentInformation(activity))
        }
    }
}

/** Slim adaptive banner, full width. Takes no space until an ad is allowed. */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    if (!Ads.ready) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

/** "Ad privacy choices" button for Settings; only shown where the law requires one. */
@Composable
fun AdPrivacyButton() {
    if (!Ads.privacyOptionsRequired) return
    val activity = LocalContext.current as? Activity ?: return
    TextButton(onClick = { Ads.showPrivacyOptions(activity) }) {
        Text("Ad privacy choices", color = MaterialTheme.colorScheme.primary)
    }
}
