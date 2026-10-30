package io.github.mattgrechtech.openpane.control

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.mattgrechtech.openpane.diag.AppLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Built-in pop-up sounds, made from tones at play time (no sound files to ship). */
object Chimes {

    private const val RATE = 22_050
    private const val TAG = "Chimes"

    /** A bell note: [freq] Hz for [seconds], starting at [start] seconds. */
    private class Note(val freq: Double, val seconds: Double, val start: Double, val decay: Double = 4.0)

    private val SOUNDS = mapOf(
        "chime" to (1.2 to listOf(Note(1046.5, 1.2, 0.0))),
        "doorbell" to (1.8 to listOf(Note(659.3, 1.0, 0.0, 2.5), Note(523.3, 1.3, 0.5, 2.0))),
        "alert" to (1.0 to listOf(Note(880.0, 0.18, 0.0, 1.0), Note(880.0, 0.18, 0.25, 1.0), Note(880.0, 0.3, 0.5, 1.0))),
    )

    val NAMES: Set<String> get() = SOUNDS.keys

    /** 16-bit mono samples for [name], or null if there's no such sound. Pure: unit-testable. */
    fun samples(name: String): ShortArray? {
        val (length, notes) = SOUNDS[name] ?: return null
        val buffer = DoubleArray((length * RATE).toInt())
        for (note in notes) {
            val first = (note.start * RATE).toInt()
            for (i in 0 until (note.seconds * RATE).toInt()) {
                val at = first + i
                if (at >= buffer.size) break
                val t = i.toDouble() / RATE
                val envelope = exp(-note.decay * t) * min(1.0, t * 200)
                val w = 2 * PI * note.freq * t
                buffer[at] += envelope * (sin(w) + 0.3 * sin(2 * w) + 0.1 * sin(3 * w))
            }
        }
        val peak = buffer.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(buffer.size) { (buffer[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Plays [name] on a background thread, then runs [then] (on that thread). */
    fun play(name: String, then: () -> Unit) {
        val pcm = samples(name) ?: return then()
        Thread({
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 100)
                track.release()
            } catch (e: Exception) {
                AppLog.w(TAG, "Can't play $name", e)
            }
            then()
        }, "chime").start()
    }
}
