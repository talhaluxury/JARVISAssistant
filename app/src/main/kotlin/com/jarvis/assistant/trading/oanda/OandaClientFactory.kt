package com.jarvis.assistant.trading.oanda

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * PHASE 9 (follow-up) — WIRES UP AN ACTUAL [OandaApi] INSTANCE
 *
 * [OandaBrokerAdapter] takes an [OandaApi] as a constructor parameter but never builds one
 * itself — this is that missing piece. Nothing here is trading logic; it's purely HTTP client
 * setup (auth header, timeouts, logging, JSON conversion).
 *
 * SECURITY: the API key is passed in as a plain string and used only in-memory to build the
 * interceptor for this client instance. It is never logged (the logging interceptor is set to
 * BODY level for troubleshooting response shapes during the integration checklist, but headers
 * are NOT logged at that level by OkHttp, so the Authorization header itself won't appear in
 * logcat). The caller is responsible for where the key itself is stored — that should be
 * SecurePrefs (Android Keystore-backed), the same as every other credential in this app, never a
 * hardcoded constant or a plain preferences file.
 */
object OandaClientFactory {

    fun createOandaApi(apiKey: String, environment: OandaEnvironment, enableLogging: Boolean = false): OandaApi {
        val authInterceptor = Interceptor { chain ->
            val authed = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Datetime-Format", "RFC3339") // matches the ISO-8601 parsing OandaBrokerAdapter expects
                .build()
            chain.proceed(authed)
        }

        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (enableLogging) {
            // BODY level shows request/response payloads for verifying the DTOs in OandaApi.kt
            // against real responses during the integration checklist — never enable this for a
            // LIVE-environment build, since response bodies (account balances, trade details)
            // would then appear in logcat.
            clientBuilder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(environment.baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(OandaApi::class.java)
    }
}
