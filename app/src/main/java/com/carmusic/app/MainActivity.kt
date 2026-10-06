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

    // 车机等大屏系统密度偏低（如 160dpi），按手机约 411dp 的短边设计整体放大。
    // 直接放大 Activity 的资源密度，对话框、底部面板等独立窗口也同步生效。
    override fun attachBaseContext(newBase: Context) {
        val metrics = newBase.resources.displayMetrics
        val shortDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        val scale = if (shortDp >= 600f) (shortDp / 411f).coerceIn(1f, 2.5f) else 1f
        if (scale == 1f) { super.attachBaseContext(newBase); return }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.densityDpi = (config.densityDpi * scale).toInt()
        config.screenWidthDp = (config.screenWidthDp / scale).toInt()
        config.screenHeightDp = (config.screenHeightDp / scale).toInt()
        config.smallestScreenWidthDp = (config.smallestScreenWidthDp / scale).toInt()
        super.attachBaseContext(newBase.createConfigurationContext(config))
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


    // 前台时系统会先把按键交给 Activity；这里兜底转给播放服务，避免车机方向盘按键被丢弃。
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val service = playbackService
        if (service != null && service.handleMediaKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }
    override fun onStart(){super.onStart();com.carmusic.app.data.AppStore.appVisible.value=true}
    override fun onStop(){com.carmusic.app.data.AppStore.appVisible.value=false;super.onStop()}
}
