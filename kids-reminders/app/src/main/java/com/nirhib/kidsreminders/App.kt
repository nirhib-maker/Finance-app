package com.nirhib.kidsreminders

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Work.scheduleDigest(this)
    }
}
