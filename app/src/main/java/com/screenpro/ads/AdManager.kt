package com.screenpro.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * AdManager
 * Central coordinator for all Google AdMob ad placements across Free Screen Recorder:
 * - App Open Ad: Shown upon app launch
 * - Rewarded Ad: Shown when user taps "Save to Phone"
 * - Native Advanced Ad: Interleaved inside Video Library grid & Settings tabs
 */
object AdManager {
    const val TAG = "AdManager"

    const val APP_ID = "ca-app-pub-8155064094205693~4564582413"
    const val REWARD_AD_UNIT_ID = "ca-app-pub-8155064094205693/2593025198"
    const val NATIVE_ADVANCED_AD_UNIT_ID = "ca-app-pub-8155064094205693/2401453501"
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-8155064094205693/1785893339"

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        MobileAds.initialize(context) { status ->
            Log.d(TAG, "AdMob initialized successfully: $status")
            isInitialized = true
            // Preload initial ads
            AppOpenManager.preload(context)
            RewardAdManager.preload(context)
        }
    }
}

/**
 * AppOpenManager
 * Preloads and displays App Open ads when the user enters the application.
 */
object AppOpenManager {
    private var appOpenAd: AppOpenAd? = null
    private var isLoading = false
    private var loadTime: Long = 0
    private var isShowingAd = false

    fun preload(context: Context) {
        if (isLoading || isAdAvailable()) return
        isLoading = true

        val request = AdRequest.Builder().build()
        AppOpenAd.load(
            context.applicationContext,
            AdManager.APP_OPEN_AD_UNIT_ID,
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoading = false
                    loadTime = System.currentTimeMillis()
                    Log.d(AdManager.TAG, "AppOpenAd loaded successfully")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isLoading = false
                    appOpenAd = null
                    Log.w(AdManager.TAG, "AppOpenAd failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    private fun isAdAvailable(): Boolean {
        return appOpenAd != null && (System.currentTimeMillis() - loadTime) < 4 * 3600 * 1000
    }

    fun showIfAvailable(activity: Activity, onDismiss: () -> Unit = {}) {
        if (isShowingAd) {
            onDismiss()
            return
        }

        if (!isAdAvailable()) {
            preload(activity.applicationContext)
            onDismiss()
            return
        }

        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                preload(activity.applicationContext)
                onDismiss()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                isShowingAd = false
                preload(activity.applicationContext)
                onDismiss()
            }

            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
            }
        }

        isShowingAd = true
        appOpenAd?.show(activity)
    }
}

/**
 * RewardAdManager
 * Preloads and triggers rewarded video ads for "Save to Phone" actions.
 */
object RewardAdManager {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun preload(context: Context) {
        if (isLoading || rewardedAd != null) return
        isLoading = true

        val request = AdRequest.Builder().build()
        RewardedAd.load(
            context.applicationContext,
            AdManager.REWARD_AD_UNIT_ID,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.d(AdManager.TAG, "RewardedAd loaded successfully")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.w(AdManager.TAG, "RewardedAd failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    fun showRewardAd(
        activity: Activity,
        onRewardGranted: () -> Unit,
        onComplete: () -> Unit = {}
    ) {
        val ad = rewardedAd
        if (ad != null) {
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    preload(activity.applicationContext)
                    if (earned) {
                        onRewardGranted()
                    }
                    onComplete()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    rewardedAd = null
                    preload(activity.applicationContext)
                    // Still reward user if ad display failed
                    onRewardGranted()
                    onComplete()
                }
            }

            ad.show(activity) {
                earned = true
            }
        } else {
            // If ad is not ready, grant reward immediately to ensure great UX
            preload(activity.applicationContext)
            onRewardGranted()
            onComplete()
        }
    }
}
