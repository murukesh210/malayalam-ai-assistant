package com.malayalam.assistant.ai

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared singleton `OkHttpClient` with HTTP/2 connection pooling (`ConnectionPool(5, 5, TimeUnit.MINUTES)`)
 * so every Gemini Brain + Streaming TTS request reuses the warm TLS socket to `generativelanguage.googleapis.com`.
 */
object SharedGeminiHttp {

    private val sharedPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Ultra-tight timeout client for Tier-2 Background Notification semantic matching (~0.8s-1.2s).
     * Reuses the same HTTP/2 ConnectionPool for warm TLS sockets.
     */
    val ultraFastNotificationClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()
    }
}
