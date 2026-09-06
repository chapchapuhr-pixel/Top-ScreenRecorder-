package com.screenpro.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * LibraryNativeAdCard
 * Pulls live Native Advanced ads directly from Google AdMob (Unit ID: 2401453501).
 * Strictly renders real AdMob views with zero mock/fake fallback ads.
 * If no ad is returned by AdMob, collapses gracefully without displaying fake placeholders.
 */
@Composable
fun LibraryNativeAdCard(
    modifier: Modifier = Modifier,
    adSlotIndex: Int = 1
) {
    val context = LocalContext.current
    var liveNativeAd by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(adSlotIndex) {
        val adLoader = AdLoader.Builder(context, AdManager.NATIVE_ADVANCED_AD_UNIT_ID)
            .forNativeAd { ad ->
                liveNativeAd = ad
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    liveNativeAd = null
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())

        onDispose {
            liveNativeAd?.destroy()
        }
    }

    // Strictly show ONLY when a real AdMob live ad is received
    val ad = liveNativeAd
    if (ad != null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF141414),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                factory = { ctx ->
                    buildNativeAdView(ctx, isCompact = false)
                },
                update = { nativeAdView ->
                    bindNativeAd(nativeAdView, ad, isCompact = false)
                }
            )
        }
    }
}

/**
 * SettingsSmallAdCard
 * Displays a live compact Native Advanced ad from AdMob at the base of settings tabs.
 * Never renders fake ads or simulated promotions.
 */
@Composable
fun SettingsSmallAdCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var liveNativeAd by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(Unit) {
        val adLoader = AdLoader.Builder(context, AdManager.NATIVE_ADVANCED_AD_UNIT_ID)
            .forNativeAd { ad ->
                liveNativeAd = ad
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    liveNativeAd = null
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())

        onDispose {
            liveNativeAd?.destroy()
        }
    }

    val ad = liveNativeAd
    if (ad != null) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF181818),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                factory = { ctx ->
                    buildNativeAdView(ctx, isCompact = true)
                },
                update = { nativeAdView ->
                    bindNativeAd(nativeAdView, ad, isCompact = true)
                }
            )
        }
    }
}

private fun dpToPx(context: Context, dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}

/**
 * Builds the NativeAdView hierarchy using pure Android UI elements for AdMob compliance.
 */
private fun buildNativeAdView(context: Context, isCompact: Boolean): NativeAdView {
    val nativeAdView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dpToPx(context, 10)
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // Top Header: "Ad" badge + Advertiser / Attribution
    val headerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val adBadge = TextView(context).apply {
        text = "Ad"
        textSize = 10f
        setTextColor(android.graphics.Color.BLACK)
        setTypeface(null, Typeface.BOLD)
        val badgeBg = GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#FFB300"))
            cornerRadius = dpToPx(context, 4).toFloat()
        }
        background = badgeBg
        val pX = dpToPx(context, 5)
        val pY = dpToPx(context, 2)
        setPadding(pX, pY, pX, pY)
    }

    val advertiserView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        setPadding(dpToPx(context, 6), 0, 0, 0)
        maxLines = 1
    }

    headerLayout.addView(adBadge)
    headerLayout.addView(advertiserView)
    container.addView(headerLayout)

    // MediaView (Large view for video/image ad creative in library cards)
    if (!isCompact) {
        val mediaContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(context, 130)
            ).apply {
                topMargin = dpToPx(context, 6)
                bottomMargin = dpToPx(context, 6)
            }
        }
        val mediaView = MediaView(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mediaContainer.addView(mediaView)
        container.addView(mediaContainer)
        nativeAdView.mediaView = mediaView
    }

    // Middle Row: Icon + Headline + Body
    val contentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(context, 4)
        }
    }

    val iconView = ImageView(context).apply {
        id = View.generateViewId()
        val iconSize = dpToPx(context, if (isCompact) 36 else 42)
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            rightMargin = dpToPx(context, 8)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val textCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        )
    }

    val headlineView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 13f
        setTextColor(android.graphics.Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        maxLines = 1
    }

    val bodyView = TextView(context).apply {
        id = View.generateViewId()
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
        maxLines = if (isCompact) 1 else 2
    }

    textCol.addView(headlineView)
    textCol.addView(bodyView)

    contentRow.addView(iconView)
    contentRow.addView(textCol)
    container.addView(contentRow)

    // Call To Action Button
    val ctaButton = Button(context).apply {
        id = View.generateViewId()
        val btnHeight = dpToPx(context, 34)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            btnHeight
        ).apply {
            topMargin = dpToPx(context, 8)
        }
        textSize = 11f
        setTextColor(android.graphics.Color.BLACK)
        setTypeface(null, Typeface.BOLD)
        val btnBg = GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#FFB300"))
            cornerRadius = dpToPx(context, 8).toFloat()
        }
        background = btnBg
    }

    container.addView(ctaButton)
    nativeAdView.addView(container)

    nativeAdView.headlineView = headlineView
    nativeAdView.bodyView = bodyView
    nativeAdView.callToActionView = ctaButton
    nativeAdView.iconView = iconView
    nativeAdView.advertiserView = advertiserView

    return nativeAdView
}

private fun bindNativeAd(nativeAdView: NativeAdView, nativeAd: NativeAd, isCompact: Boolean) {
    (nativeAdView.headlineView as? TextView)?.text = nativeAd.headline ?: ""

    (nativeAdView.bodyView as? TextView)?.apply {
        if (nativeAd.body != null) {
            visibility = View.VISIBLE
            text = nativeAd.body
        } else {
            visibility = View.GONE
        }
    }

    (nativeAdView.callToActionView as? Button)?.apply {
        if (nativeAd.callToAction != null) {
            visibility = View.VISIBLE
            text = nativeAd.callToAction
        } else {
            visibility = View.GONE
        }
    }

    (nativeAdView.iconView as? ImageView)?.apply {
        if (nativeAd.icon?.drawable != null) {
            visibility = View.VISIBLE
            setImageDrawable(nativeAd.icon?.drawable)
        } else {
            visibility = View.GONE
        }
    }

    (nativeAdView.advertiserView as? TextView)?.apply {
        if (nativeAd.advertiser != null) {
            visibility = View.VISIBLE
            text = nativeAd.advertiser
        } else {
            visibility = View.GONE
        }
    }

    if (!isCompact && nativeAdView.mediaView != null) {
        nativeAd.mediaContent?.let { mediaContent ->
            nativeAdView.mediaView?.setMediaContent(mediaContent)
        }
    }

    nativeAdView.setNativeAd(nativeAd)
}
