package com.carmusic.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import com.carmusic.app.ui.CarMusicScreen
import com.carmusic.app.ui.theme.AccentBlue
import com.carmusic.app.ui.theme.CarMusicTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var playbackService by mutableStateOf<PlaybackService?>(null)
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            playbackService = binder?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 启动并绑定播放服务
        startService(Intent(this, PlaybackService::class.java))
        val intent = PlaybackService.localBindingIntent(this)
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val storeReady by com.carmusic.app.data.AppStore.loaded.collectAsState()
            if (!storeReady) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentBlue)
                }
                return@setContent
            }
            CarMusicTheme {
                val service = playbackService
                val ready = service?.ready?.collectAsState()?.value == true
                if (service != null && ready) {
                    CarMusicScreen(service = service)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBlue)
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }
    override fun onStart(){super.onStart();com.carmusic.app.data.AppStore.appVisible.value=true}
    override fun onStop(){com.carmusic.app.data.AppStore.appVisible.value=false;super.onStop()}
}
