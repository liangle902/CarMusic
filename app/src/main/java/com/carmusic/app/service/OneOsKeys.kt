package com.carmusic.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/**
 * 吉利 oneOS 车机的方向盘媒体键不走安卓媒体按键通道，而是由车机按键服务分发给注册的监听者。
 * 这里旁路监听（不拦截）；车机媒体中心在音频焦点属于未接入应用时不处理这些按键。
 * 非吉利设备上入口服务不存在，绑定失败后什么都不做。
 */
internal class OneOsKeys(context: Context, private val onShortClick: (Int) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var input: IBinder? = null
    private var bound = false

    private val listener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..6) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(INPUT_LISTENER)
            val keyCode = data.readInt()
            if (code == SHORT_CLICK) main.post { onShortClick(keyCode) }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            runCatching {
                val manager = binder ?: return
                val reply = call(manager, SERVICE_MANAGER, GET_SERVICE) { writeInt(KEY_INPUT_SERVICE) }
                val service = reply.readStrongBinder().also { reply.recycle() } ?: return
                call(service, INPUT_MANAGER, REGISTER_LISTENER) { writeStrongBinder(listener); writeString(app.packageName); writeIntArray(KEYS) }.recycle()
                input = service
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { input = null }
    }

    fun start() {
        if (bound) return
        bound = runCatching {
            app.bindService(Intent().setClassName("com.geely.service.oneosapi", "com.geely.service.oneosapi.OneOSApiService"), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun stop() {
        if (!bound) return
        bound = false
        runCatching { input?.let { call(it, INPUT_MANAGER, UNREGISTER_LISTENER) { writeStrongBinder(listener); writeString(app.packageName) }.recycle() } }
        input = null
        runCatching { app.unbindService(connection) }
    }

    private fun call(binder: IBinder, descriptor: String, code: Int, args: Parcel.() -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.args()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply
        } finally {
            data.recycle()
        }
    }

    companion object {
        const val KEY_PLAY_PAUSE = 200085
        const val KEY_NEXT = 200087
        const val KEY_PREVIOUS = 200088
        private val KEYS = intArrayOf(KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS)
        private const val SERVICE_MANAGER = "com.geely.lib.oneosapi.IServiceManager"
        private const val INPUT_MANAGER = "com.geely.lib.oneosapi.input.IInputManager"
        private const val INPUT_LISTENER = "com.geely.lib.oneosapi.input.IInputListener"
        private const val GET_SERVICE = 2
        private const val KEY_INPUT_SERVICE = 8
        private const val REGISTER_LISTENER = 3
        private const val UNREGISTER_LISTENER = 4
        private const val SHORT_CLICK = 2
    }
}
