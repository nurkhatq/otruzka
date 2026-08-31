package com.otgruzka.tsd.api

import android.content.Context
import android.content.Intent
import com.otgruzka.tsd.CoreAuth
import com.otgruzka.tsd.PickerLoginActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Клиент прод-ядра novamanya (qoimams.asia). Используется только разделом «Сборка». */
object CoreApiClient {

    private const val BASE_URL = "https://qoimams.asia/api/"

    private var _api: CoreApi? = null

    fun build(context: Context): CoreApi {
        _api?.let { return it }

        val appCtx = context.applicationContext

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val token = CoreAuth.token(appCtx)
                val req = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else chain.request()
                chain.proceed(req)
            }
            .authenticator { _, response ->
                if (response.priorResponse != null) return@authenticator null
                val username = CoreAuth.username(appCtx)
                val password = CoreAuth.password(appCtx)
                if (username == null || password == null) {
                    CoreAuth.logout(appCtx)
                    appCtx.startActivity(
                        Intent(appCtx, PickerLoginActivity::class.java)
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
                            .create(CoreApi::class.java)
                            .login(username, password)
                    }
                    CoreAuth.setToken(appCtx, newResp.access_token)
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${newResp.access_token}")
                        .build()
                } catch (_: Exception) {
                    CoreAuth.logout(appCtx)
                    appCtx.startActivity(
                        Intent(appCtx, PickerLoginActivity::class.java)
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
            .create(CoreApi::class.java)

        _api = api
        return api
    }

    fun reset() { _api = null }
}
