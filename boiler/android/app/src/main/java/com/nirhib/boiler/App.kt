package com.nirhib.boiler

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Sync.schedule(this)
    }
}
