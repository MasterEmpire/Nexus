package com.conduit.nexus

import android.app.Application
import android.content.Context

class NexusApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashBootstrap.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashBootstrap.install(this)
    }
}