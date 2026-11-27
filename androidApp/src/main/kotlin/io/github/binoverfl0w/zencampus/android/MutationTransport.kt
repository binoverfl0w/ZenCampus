package io.github.binoverfl0w.zencampus.android

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink

/** A mutation body cannot be replayed by HTTP follow-up or connection recovery. GET retries belong to the adapter. */
internal fun OkHttpClient.Builder.singleAttemptMutations(): OkHttpClient.Builder = apply {
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val body = request.body
        if (request.method in setOf("GET", "HEAD") || body == null) chain.proceed(request)
        else chain.proceed(request.newBuilder().method(request.method, object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun isDuplex() = body.isDuplex()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }).build())
    })
}
