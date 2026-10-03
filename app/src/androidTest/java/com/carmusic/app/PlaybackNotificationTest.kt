package com.carmusic.app

import android.app.Notification
import android.app.NotificationManager
import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.carmusic.app.data.AppStore
import com.carmusic.app.engine.ApiClient
import com.carmusic.app.service.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PlaybackNotificationTest {
    @Test fun notificationControlsLyricsAndOptionalForegroundCardWork():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val manager=context.getSystemService(NotificationManager::class.java)
        val directory=File(context.filesDir,"data/downloads").apply {mkdirs()}
        val audio=File(directory,"notification-test.wav");val lrc=File(directory,"notification-test.lrc")
        val pcm=ByteArray(44100*2*30)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
        audio.outputStream().use {it.write(header);it.write(pcm)};lrc.writeText("[00:00]Notification lyric one\n[00:20]Notification lyric two")
        val future=CompletableFuture<PlaybackService>()
        val connection=object:ServiceConnection {override fun onServiceConnected(name:ComponentName,binder:IBinder){future.complete((binder as PlaybackService.LocalBinder).getService())};override fun onServiceDisconnected(name:ComponentName){}}
        val enabled=AppStore.notificationControls.value
        try {
            val song=ApiClient.localSongs().first {it.extra?.get("filename")==audio.name}
            val second=song.copy(id=song.id,name="Notification second")
            // Same media file, distinct source identity for the queue controls.
            val next=second.copy(source="local",id=android.net.Uri.fromFile(audio).toString(),streamUrl=android.net.Uri.fromFile(audio).toString())
            instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.carmusic.app.MainActivity").use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            assertTrue(context.bindService(Intent(context,PlaybackService::class.java),connection,Context.BIND_AUTO_CREATE))
            val service=future.get(15,TimeUnit.SECONDS)
            fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
            fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
            fun await(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end&&!condition())Thread.sleep(100);assertTrue(label,condition())}
            fun card()=manager.activeNotifications.firstOrNull {it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()==service.currentSong.value?.name}?.notification
            main {AppStore.setNotificationControls(true);service.replaceQueue(listOf(song,next))}
            await("系统应展示含歌曲和歌词的媒体通知"){card()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()=="Notification lyric one"}
            assertNotNull(card()!!.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION))
            assertEquals(3,card()!!.actions.size)
            await("系统媒体元数据应包含当前歌词"){
                var matches=false
                main {matches=service.player.mediaMetadata.artist?.toString()?.contains("Notification lyric one")==true}
                matches
            }
            main {service.player.pause()}
            shell("cmd statusbar expand-notifications")
            try {
                await("通知抽屉应实际显示当前歌词"){instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Notification lyric one")?.isNotEmpty()==true}
            } finally {shell("cmd statusbar collapse")}
            main {service.player.play()}
            card()!!.actions[1].actionIntent.send();await("通知暂停应控制实际播放器"){!service.isPlaying.value}
            card()!!.actions[1].actionIntent.send();await("通知播放应恢复实际播放器"){service.isPlaying.value}
            card()!!.actions[2].actionIntent.send();await("通知下一首应切换队列曲目"){service.currentSong.value?.key==next.key&&service.isPlaying.value}
            await("切歌后系统应完成异步通知更新"){card()?.actions?.size==3}
            card()!!.actions[0].actionIntent.send();await("通知上一首应返回原曲"){service.currentSong.value?.key==song.key&&service.isPlaying.value}
            main {AppStore.setNotificationControls(false)}
            await("关闭开关应隐藏前台通知卡片"){manager.activeNotifications.isEmpty()}
            assertTrue(service.isPlaying.value)
            shell("input keyevent 3")
            await("后台播放必须继续显示系统必需通知"){!AppStore.appVisible.value&&manager.activeNotifications.isNotEmpty()&&service.isPlaying.value}
        } finally {
            if(future.isDone) instrumentation.runOnMainSync {future.get().clearQueue();AppStore.setNotificationControls(enabled)}
            runCatching {context.unbindService(connection)};audio.delete();lrc.delete()
        }
    }
}
