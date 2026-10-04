package com.carmusic.app

import android.app.Application
import android.util.Log
import com.carmusic.app.engine.DaemonManager
import com.carmusic.app.engine.ApiClient
import coil.ImageLoader
import coil.ImageLoaderFactory

class CarMusicApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(ApiClient.httpClient).build()

    companion object {
        lateinit var instance: CarMusicApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("CarMusic", "Application starting, initializing Go daemon engine...")
        DaemonManager.ensureDaemonRunning(this)
        com.carmusic.app.data.LocalMusicScanner.start(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        DaemonManager.stopDaemon()
    }
}
