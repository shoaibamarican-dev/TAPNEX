package com.tapnex.autoclicker

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.tapnex.autoclicker.model.PointStore

class TapnexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Saved click points must be readable by the accessibility service even when no Activity is open.
        PointStore.initialize(this)
        MobileAds.initialize(this) {}
    }
}
