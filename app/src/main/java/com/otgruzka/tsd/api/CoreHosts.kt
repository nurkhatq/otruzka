package com.otgruzka.tsd.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * Адреса одного и того же сервера ядра, по порядку предпочтения.
 *
 * 16.11.2026 сервер сменил IP (185.185.49.163 → 185.253.8.128), а DNS
 * qoimams.asia еще смотрел на старый, мертвый. Запасное имя sslip.io ведет на
 * новый IP без всякого DNS. Приложение ходит на основной адрес; если до него
 * не достучаться, переключается на запасной и раз в минуту фоном проверяет основной.
 * Как только DNS qoimams.asia починят, терминалы сами вернутся на него.
 *
 * Переключение только при ошибке СОЕДИНЕНИЯ (запрос до сервера не дошел),
 * поэтому повтор безопасен и для отгрузки: скан не спишется дважды. Ошибка
 * после отправки (таймаут чтения, обрыв ответа) пробрасывается как была.
 */
object CoreHosts {

    const val PRIMARY = "qoimams.asia"
    val HOSTS = listOf(PRIMARY, "qoimams.185-253-8-128.sslip.io")

    private const val RETRY_PRIMARY_MS = 60_000L

    @Volatile private var current = PRIMARY
    @Volatile private var lastPrimaryTry = 0L

    /** Адрес, на котором сейчас работаем (для ссылок вне OkHttp: скачивание APK). */
    fun currentHost(): String = current

    /** Тот же URL, но на рабочем адресе. Чужие хосты не трогаем. */
    fun rewrite(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host !in HOSTS) return url
        return parsed.newBuilder().host(current).build().toString()
    }

    private fun order(): List<String> {
        val cur = current
        if (cur != PRIMARY) probePrimaryInBackground()
        return listOf(cur) + HOSTS.filter { it != cur }
    }

    @Volatile private var probing = false

    /** Работаем на запасном: раз в минуту фоном проверяем основной. Сканы не
     *  ждут проверку — им не нужны лишние 4 с на мертвом адресе. */
    private fun probePrimaryInBackground() {
        val now = System.currentTimeMillis()
        if (probing || now - lastPrimaryTry < RETRY_PRIMARY_MS) return
        probing = true
        lastPrimaryTry = now
        Thread {
            try {
                probeClient.newCall(
                    Request.Builder().url("https://$PRIMARY/api/health").build()
                ).execute().use { if (it.isSuccessful) current = PRIMARY }
            } catch (_: Exception) {
            } finally {
                probing = false
            }
        }.start()
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun isConnectFailure(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException,
        is NoRouteToHostException, is SSLHandshakeException -> true
        is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.host !in HOSTS) return@Interceptor chain.proceed(req)
        var last: IOException? = null
        for (host in order()) {
            try {
                val resp: Response = chain.proceed(
                    req.newBuilder().url(req.url.newBuilder().host(host).build()).build()
                )
                current = host
                return@Interceptor resp
            } catch (e: IOException) {
                if (!isConnectFailure(e)) throw e
                last = e
            }
        }
        throw last ?: IOException("Сервер недоступен")
    }
}
