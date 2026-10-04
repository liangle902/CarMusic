package com.carmusic.app.engine

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Network interceptors run again on redirects, so tokens never follow a CDN hop. */
internal class EngineTokenInterceptor(private val authorize: (HttpUrl) -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().removeHeader("Authorization")
        authorize(request.url)?.let { builder.header("Authorization", it) }
        return chain.proceed(builder.build())
    }
}
