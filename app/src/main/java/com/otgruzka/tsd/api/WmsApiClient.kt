package com.otgruzka.tsd.api

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object WmsApiClient {

    private const val BASE_URL = "http://194.238.41.18/api/v1/"

    private var _api: WmsApi? = null

    fun build(context: Context): WmsApi {
        _api?.let { return it }

        val prefs = context.getSharedPreferences("wms_auth", Context.MODE_PRIVATE)
        val token = prefs.getString("token", null)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else chain.request()
                chain.proceed(req)
            }
            .build()

        val api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WmsApi::class.java)

        _api = api
        return api
    }

    fun reset() { _api = null }
}
