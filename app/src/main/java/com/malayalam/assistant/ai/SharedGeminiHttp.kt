package com.malayalam.assistant.ai

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Bulletproof shared singleton `OkHttpClient` for Gemini Brain + Streaming TTS:
 *  - `pingInterval(20, TimeUnit.SECONDS)` keeps HTTP/2 connections warm and instantly detects dead carrier sockets.
 *  - `ConnectionPool(4, 90, TimeUnit.SECONDS)` evicts idle sockets before mobile carrier NATs silently drop them.
 *  - `retryOnConnectionFailure(true)` with balanced `connect=8s`, `read=12s`, `write=8s` timeouts.
 *  - Exposes `evictStaleSockets()` for 1x automatic fresh-socket retry on `IOException` / `SocketTimeoutException` / `EOFException`.
 */
object SharedGeminiHttp {

    private val sharedPool = ConnectionPool(4, 90, TimeUnit.SECONDS)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Slim timeout client for Tier-2 Background Notification semantic matching.
     * Shares the exact same `ConnectionPool` and `pingInterval(20s)`.
     */
    val ultraFastNotificationClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun evictStaleSockets() {
        runCatching { sharedPool.evictAll() }
    }

    /** Immediately aborts all in-flight Gemini Brain & Streaming TTS HTTP calls when Master Power is turned OFF. */
    fun cancelAllInFlightCalls() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { fastClient.dispatcher.cancelAll() }
        runCatching { ultraFastNotificationClient.dispatcher.cancelAll() }
    }
}
