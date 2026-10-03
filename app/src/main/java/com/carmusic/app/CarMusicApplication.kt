package com.carmusic.app

import android.app.Application
import android.util.Log
import com.carmusic.app.engine.DaemonManager

class CarMusicApplication : Application() {

    companion object {
        lateinit var instance: CarMusicApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("CarMusic", "Application starting, initializing Go daemon engine...")
        DaemonManager.ensureDaemonRunning(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        DaemonManager.stopDaemon()
    }
}
