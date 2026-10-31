package io.github.mattgrechtech.openpane.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Push-to-talk to Home Assistant's Assist: records from the microphone at 16 kHz and streams it
 * to an Assist pipeline over Home Assistant's WebSocket; Home Assistant turns it into text, acts
 * on it and answers, and the answer is spoken here. No speech model in the app.
 *
 * The microphone is opened only for one request at a time (at most [MAX_LISTEN_MILLIS]), only
 * when voice is on and someone asked, and [onStatus] shows each step on screen ("Listening…").
 */
class Voice(
    private val context: Context,
    private val settings: Settings,
    private val auth: HaAuth,
    /** Shown to the user: (title, message, done). */
    private val onStatus: (String, String, Boolean) -> Unit,
) {

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var conversationId: String? = null
    private var conversationAt = 0L
    private var player: MediaPlayer? = null

    fun hasMicPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening, or stops early if already listening (a second press). Returns a problem, or null. */
    fun toggle(): String? {
        if (!settings[Keys.VOICE]) return "voice is off (Settings → voice)"
        if (!hasMicPermission()) return "the microphone isn't allowed: open the dashboard to allow it"
        if (settings.isWebMode() || settings.haBaseUrl() == null) return "voice needs the app signed in to Home Assistant"
        if (running) {
            stopRequested = true
            return null
        }
        running = true
        stopRequested = false
        Thread({ run() }, "voice").start()
        return null
    }

    @SuppressLint("MissingPermission") // Checked in toggle().
    private fun run() {
        val base = settings.haBaseUrl() ?: return
        var record: AudioRecord? = null
        try {
            onStatus("Listening…", "", false)
            val token = auth.accessToken(force = false).accessToken
            MiniWebSocket.connect("$base/api/websocket").use { ws ->
                ws.setReadTimeout(30_000)
                if (JSONObject(ws.receiveText()).optString("type") != "auth_required") throw IllegalStateException("unexpected answer")
                ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
                if (JSONObject(ws.receiveText()).optString("type") != "auth_ok") throw IllegalStateException("Home Assistant refused the sign-in")

                val run = JSONObject().put("id", 1).put("type", "assist_pipeline/run")
                    .put("start_stage", "stt").put("end_stage", "tts")
                    .put("input", JSONObject().put("sample_rate", SAMPLE_RATE))
                settings[Keys.VOICE_PIPELINE].takeIf { it.isNotEmpty() }?.let { run.put("pipeline", it) }
                // Follow-ups within a few minutes continue the same conversation.
                if (conversationId != null && SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_MILLIS) {
                    run.put("conversation_id", conversationId)
                }
                ws.sendText(run.toString())

                var handler = -1
                while (handler < 0) {
                    val message = JSONObject(ws.receiveText())
                    if (message.optString("type") == "result" && !message.optBoolean("success")) {
                        throw IllegalStateException(message.optJSONObject("error")?.optString("message") ?: "Assist couldn't start")
                    }
                    val event = message.optJSONObject("event") ?: continue
                    if (event.optString("type") == "run-start") {
                        val data = event.optJSONObject("data")
                        handler = data?.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                        // Newer Home Assistant names the conversation here; older ones only in the answer.
                        data?.optString("conversation_id")?.takeIf { it.isNotEmpty() }?.let { conversationId = it }
                    }
                }

                // Audio goes out on this thread; Home Assistant's answers come in on another.
                val buffer = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK_BYTES * 4)
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
                val session = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.enabled = true
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.enabled = true

                val speechEnded = AtomicBoolean(false)
                val failure = AtomicReference<String?>(null)
                var answer = ""
                var heard = ""
                var ttsUrl: String? = null
                val reader = Thread({
                    try {
                        while (true) {
                            val message = JSONObject(ws.receiveText())
                            val event = message.optJSONObject("event") ?: continue
                            val data = event.optJSONObject("data") ?: JSONObject()
                            when (event.optString("type")) {
                                "stt-vad-end", "stt-end" -> {
                                    speechEnded.set(true)
                                    data.optJSONObject("stt_output")?.optString("text")?.let { if (it.isNotEmpty()) heard = it }
                                    if (heard.isNotEmpty()) onStatus("“$heard”", "…", false)
                                }
                                "intent-end" -> {
                                    val output = data.optJSONObject("intent_output")
                                    conversationId = output?.optString("conversation_id")?.takeIf { it.isNotEmpty() }
                                    conversationAt = SystemClock.elapsedRealtime()
                                    answer = output?.optJSONObject("response")?.optJSONObject("speech")
                                        ?.optJSONObject("plain")?.optString("speech").orEmpty()
                                }
                                "tts-end" -> ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                                "error" -> failure.set(data.optString("message").ifEmpty { data.optString("code") })
                                "run-end" -> return@Thread
                            }
                        }
                    } catch (e: Exception) {
                        if (failure.get() == null && !speechEnded.get()) failure.set("lost the connection")
                    }
                }, "voice-events")
                reader.start()

                record.startRecording()
                val started = SystemClock.elapsedRealtime()
                val chunk = ByteArray(CHUNK_BYTES)
                val frame = ByteArray(CHUNK_BYTES + 1)
                frame[0] = handler.toByte()
                while (!speechEnded.get() && !stopRequested && failure.get() == null && SystemClock.elapsedRealtime() - started < MAX_LISTEN_MILLIS) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    System.arraycopy(chunk, 0, frame, 1, n)
                    ws.sendBinary(if (n == CHUNK_BYTES) frame else frame.copyOf(n + 1))
                }
                record.stop()
                // The handler id alone says the audio has ended.
                ws.sendBinary(byteArrayOf(handler.toByte()))
                reader.join(20_000)

                failure.get()?.let { throw IllegalStateException(it) }
                onStatus(if (heard.isEmpty()) "Assist" else "“$heard”", answer.ifEmpty { "Done" }, true)
                ttsUrl?.takeIf { it.isNotEmpty() }?.let { url -> speak(if (url.startsWith("http")) url else base + url) }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Assist: ${e.message ?: e.javaClass.simpleName}")
            onStatus("Assist", "Didn't work: ${e.message ?: e.javaClass.simpleName}", true)
        } finally {
            runCatching { record?.release() }
            running = false
        }
    }

    private fun speak(url: String) {
        // The answer's audio from Home Assistant's TTS proxy; the link carries its own short-lived id.
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { it.release(); if (player === it) player = null }
            setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; true }
            prepareAsync()
        }
    }

    private companion object {
        const val TAG = "Voice"
        const val SAMPLE_RATE = 16_000
        /** 20 ms of 16-bit mono audio at 16 kHz. */
        const val CHUNK_BYTES = 640
        const val MAX_LISTEN_MILLIS = 12_000L
        const val CONVERSATION_MILLIS = 5 * 60_000L
    }
}
