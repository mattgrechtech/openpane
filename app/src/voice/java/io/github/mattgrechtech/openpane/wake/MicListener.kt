package io.github.mattgrechtech.openpane.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import io.github.mattgrechtech.openpane.diag.AppLog

/**
 * Listens on the microphone for the wake word, on one low-priority thread. The audio never leaves
 * the device and isn't kept: each 10 ms becomes 40 numbers for the model, then is gone.
 */
class MicListener(private val context: Context, private val onWake: () -> Unit) : WakeWordListener {

    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false

    override val running: Boolean get() = thread != null

    override fun start(word: String) {
        if (thread != null) return
        stopRequested = false
        thread = Thread({ run(word) }, "wake-word").also { it.start() }
    }

    override fun stop() {
        stopRequested = true
        thread?.let { if (it !== Thread.currentThread()) it.join(1000) }
        thread = null
    }

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO.
    private fun run(word: String) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var record: AudioRecord? = null
        var model: WakeWordModel? = null
        try {
            model = WakeWordModel(context, word)
            val frontend = MicroFrontend()
            val size = maxOf(
                AudioRecord.getMinBufferSize(MicroFrontend.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                MicroFrontend.STEP_SAMPLES * 2 * 8,
            )
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicroFrontend.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("the microphone didn't open")
            record.startRecording()
            AppLog.i(TAG, "Listening for $word")
            val samples = ShortArray(MicroFrontend.STEP_SAMPLES)
            val features = IntArray(MicroFrontend.CHANNELS)
            while (!stopRequested) {
                var read = 0
                while (read < samples.size && !stopRequested) {
                    val n = record.read(samples, read, samples.size - read)
                    if (n < 0) throw IllegalStateException("the microphone stopped ($n)")
                    read += n
                }
                if (stopRequested) break
                var at = 0
                while (at < samples.size) {
                    val (used, ready) = frontend.process(samples, at, samples.size - at, features)
                    at += used
                    if (ready && model.add(features)) {
                        AppLog.i(TAG, "Heard $word")
                        onWake()
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Wake word stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { model?.close() }
            if (thread === Thread.currentThread()) thread = null
        }
    }

    private companion object {
        const val TAG = "WakeWord"
    }
}
