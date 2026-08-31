package com.otgruzka.tsd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Звуковой фидбек сканов сборщика — AudioTrack на всю громкость, слышно в цеху. */
object Beep {

    /** Совпало — один чистый приятный тон. */
    fun ok() = play(880, 180, 1, 0, square = false)

    /** Записано с оговоркой (неизвестный ШК / без ШК) — два средних тона. */
    fun warn() = play(660, 200, 2, 100, square = false)

    /** Ошибка / не тот товар — резкие высокие. */
    fun error() = play(1900, 180, 4, 70, square = true)

    private fun play(freqHz: Int, durationMs: Int, count: Int, gapMs: Int, square: Boolean) {
        Thread {
            try {
                val sampleRate = 22050
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack(attrs, fmt, minBuf.coerceAtLeast(4096),
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                track.setVolume(1.0f)
                track.play()

                val samplesPerMs = sampleRate / 1000
                val toneSamples = durationMs * samplesPerMs
                val gapSamples = gapMs * samplesPerMs
                val samplesPerCycle = if (freqHz > 0) sampleRate / freqHz else 1

                val toneBuf = ShortArray(toneSamples) { i ->
                    if (square) {
                        if ((i % samplesPerCycle) < samplesPerCycle / 2) 29000 else -29000
                    } else {
                        (29000 * kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate)).toInt().toShort().toInt()
                    }.toShort()
                }
                val gapBuf = ShortArray(gapSamples)

                repeat(count) { idx ->
                    track.write(toneBuf, 0, toneSamples)
                    if (idx < count - 1 && gapSamples > 0)
                        track.write(gapBuf, 0, gapSamples)
                }
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }.start()
    }
}
