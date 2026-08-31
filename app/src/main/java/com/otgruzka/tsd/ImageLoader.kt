package com.otgruzka.tsd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Мини-загрузчик фото товара (без сторонних библиотек, кэш в памяти). */
object ImageLoader {

    private val cache = LruCache<String, Bitmap>(24)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(url: String, into: ImageView) {
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        scope.launch {
            val bmp = fetch(url) ?: return@launch
            cache.put(url, bmp)
            withContext(Dispatchers.Main) {
                if (into.tag == url) into.setImageBitmap(bmp)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcSample(bounds.outWidth, bounds.outHeight, 480)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) { null }

    private fun calcSample(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }
}
