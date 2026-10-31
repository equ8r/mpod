package com.circpros.micahpod.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Uses the default Cast receiver (same as the MicahPod web player).
 * Media3's MediaSession owns notifications, so Cast's media notification is disabled.
 */
class MicahPodCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(null as NotificationOptions?)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_RECEIVER_APP_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Same default media receiver the browser Cast SDK uses. */
        const val DEFAULT_RECEIVER_APP_ID = "CC1AD845"
    }
}
