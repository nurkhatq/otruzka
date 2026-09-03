package com.otgruzka.tsd

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Обновление приложения по кнопке: рядом с APK на сервере лежит version.json
 * (кладётся ТОЛЬКО скриптом deploy_apk.sh — руками не трогать), приложение
 * сравнивает свою версию с серверной и показывает баннер в MainActivity.
 *
 * Скачивание — системный DownloadManager (нотификация с прогрессом), установка
 * — системный установщик по content-URI DownloadManager. Первый раз Android
 * сам предложит разрешить установку из этого приложения (одноразово).
 *
 * Любая ошибка проверки — молча: обновление не должно мешать работе склада.
 */
object AppUpdater {

    private const val VERSION_URL = "https://qoimams.asia/apk/version.json"
    private const val PREFS = "app_updater"
    // Проверяем практически при каждом входе в приложение. Час, стоявший здесь
    // раньше, означал вот что: терминал спросил сервер за минуту до выкладки,
    // запомнил «обновлений нет» — и следующий час баннер не показывал, сколько
    // ни перезаходи. Запрос весит 149 байт, экономить на нём было не на чем.
    // Пары минут достаточно, чтобы не долбить сервер при частых сворачиваниях.
    private const val CHECK_INTERVAL_MS = 2 * 60 * 1000L
    private const val APK_FILENAME = "otgruzka-update.apk"

    data class Info(
        val version_code: Int = 0,
        val version_name: String? = null,
        val url: String? = null,
        val notes: String? = null,
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Есть ли обновление. Сетевой запрос не чаще раза в час (force=true —
     *  всегда); между запросами баннер держится на закэшированном ответе. */
    suspend fun check(context: Context, force: Boolean = false): Info? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val stale = now - prefs.getLong("last_check", 0) >= CHECK_INTERVAL_MS
            if (force || stale) {
                try {
                    val body = http.newCall(
                        Request.Builder().url("$VERSION_URL?t=$now").build()
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext cached(prefs)
                        resp.body?.string()
                    }
                    val info = Gson().fromJson(body, Info::class.java) ?: return@withContext null
                    prefs.edit()
                        .putLong("last_check", now)
                        .putInt("seen_code", info.version_code)
                        .putString("seen_name", info.version_name)
                        .putString("seen_url", info.url)
                        .putString("seen_notes", info.notes)
                        .apply()
                } catch (_: Exception) {
                    // сети нет / сервер лёг — работаем дальше, покажем кэш
                }
            }
            cached(prefs)
        }

    private fun cached(prefs: android.content.SharedPreferences): Info? {
        val code = prefs.getInt("seen_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        return Info(
            version_code = code,
            version_name = prefs.getString("seen_name", null),
            url = prefs.getString("seen_url", null),
            notes = prefs.getString("seen_notes", null),
        )
    }

    // Скачанный, но ещё не установленный APK (юзер мог уйти в настройки
    // разрешать установку) — повторный тап открывает установщик без перекачки
    private var pendingInstall: Uri? = null

    private fun openInstaller(app: Context, uri: Uri) {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        )
    }

    /** Скачать и открыть установщик. onStatus — текст для баннера. */
    fun startDownload(context: Context, info: Info, onStatus: (String) -> Unit) {
        val app = context.applicationContext
        pendingInstall?.let {
            onStatus("Открываю установщик…")
            openInstaller(app, it)
            return
        }
        val url = info.url ?: VERSION_URL.replace("version.json", "sborka.apk")
        val dm = app.getSystemService(DownloadManager::class.java) ?: return

        // Стереть прошлую загрузку, иначе DownloadManager сохранит «…-1.apk»
        File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME).delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление ТСД v${info.version_name ?: info.version_code}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                app, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME
            )
        val downloadId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            onStatus("Не удалось начать скачивание: ${e.message?.take(40)}")
            return
        }
        onStatus("Скачивается обновление…")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    onStatus("Скачивание не удалось — попробуйте ещё раз")
                    return
                }
                pendingInstall = uri
                onStatus("Открываю установщик… (если закрыли — нажмите ещё раз)")
                // Системный установщик; если установка «из этого приложения» ещё
                // не разрешена — Android сам предложит включить (одноразово)
                openInstaller(app, uri)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }
}
