package com.carmusic.app

import com.carmusic.app.engine.ApiClient
import com.carmusic.app.ui.decodeLoginQr
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LoginQrTest {
    @Test fun liveUpstreamSessionsProduceScannableBitmaps() = runBlocking {
        for (source in listOf("qq","qq_wx","netease","kugou","bilibili","soda")) {
            val session=ApiClient.json("/qr_login/$source",method="POST").asJsonObject
            assertTrue("$source 返回登录会话",session.get("key")?.asString?.isNotBlank()==true)
            val bitmap=requireNotNull(decodeLoginQr(session)) {"$source 未生成二维码"}
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),mapOf(DecodeHintType.TRY_HARDER to true))
            assertEquals(BarcodeFormat.QR_CODE,result.barcodeFormat)
            assertTrue("$source 二维码包含可扫码内容",result.text.isNotBlank())
            // 不记录临时登录 URL、会话密钥或账号信息。
            bitmap.recycle()
        }
    }
}
