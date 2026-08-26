package com.circpros.micahpod

import android.app.Application
import com.circpros.micahpod.data.ServiceLocator

class MicahPodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
