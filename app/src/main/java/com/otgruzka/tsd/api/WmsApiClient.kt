package com.otgruzka.tsd.api

import android.content.Context
import android.content.Intent
import com.otgruzka.tsd.LoginActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object WmsApiClient {

    private const val BASE_URL = "http://194.238.41.18/api/v1/"

    private var _api: WmsApi? = null
    private var appContext: Context? = null

    fun build(context: Context): WmsApi {
        _api?.let { return it }

        val appCtx = context.applicationContext.also { appContext = it }
        val prefs = appCtx.getSharedPreferences("wms_auth", Context.MODE_PRIVATE)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val token = prefs.getString("token", null)
                val req = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else chain.request()
                chain.proceed(req)
            }
            .authenticator { _, response ->
                if (response.priorResponse != null) return@authenticator null
                val username = prefs.getString("username", null)
                val password = prefs.getString("password", null)
                if (username == null || password == null) {
                    prefs.edit().clear().apply()
                    appCtx.startActivity(
                        Intent(appCtx, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    return@authenticator null
                }
                try {
                    val newResp = runBlocking {
                        Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .client(OkHttpClient())
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(WmsApi::class.java)
                            .login(username, password)
                    }
                    prefs.edit().putString("token", newResp.access_token).apply()
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${newResp.access_token}")
                        .build()
                } catch (_: Exception) {
                    prefs.edit().clear().apply()
                    appCtx.startActivity(
                        Intent(appCtx, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    null
                }
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
