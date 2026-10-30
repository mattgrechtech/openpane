package io.github.mattgrechtech.tvkiosk.control

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import io.github.mattgrechtech.tvkiosk.diag.AppLog

/**
 * Pauses whatever another app is playing (Netflix, YouTube, live TV) while pop-ups that ask for it
 * are on screen, and resumes it when the last one closes. Only resumes what it paused itself.
 * Uses the media keys, which reach the app playing through Android's media session.
 */
class MediaPause(private val audio: AudioManager) {

    private val main = Handler(Looper.getMainLooper())
    private val holders = mutableSetOf<String>()
    private var paused = false

    /** A pop-up [id] that wants quiet appeared: pause, if something is playing. Main thread. */
    fun hold(id: String) {
        main.removeCallbacks(resume)
        if (holders.isEmpty() && !paused && audio.isMusicActive) {
            press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            paused = true
            AppLog.i(TAG, "Paused what was playing for a pop-up")
        }
        holders += id
    }

    /** Pop-up [id] closed. Resumes a moment later, so a pop-up replaced by its id doesn't flicker it. */
    fun release(id: String) {
        if (!holders.remove(id) || holders.isNotEmpty() || !paused) return
        main.removeCallbacks(resume)
        main.postDelayed(resume, RESUME_DELAY_MILLIS)
    }

    private val resume = Runnable {
        if (holders.isNotEmpty() || !paused) return@Runnable
        paused = false
        // Someone may have started something else meanwhile: then leave it alone.
        if (!audio.isMusicActive) {
            press(KeyEvent.KEYCODE_MEDIA_PLAY)
            AppLog.i(TAG, "Resumed after the pop-up")
        }
    }

    private fun press(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private companion object {
        const val TAG = "MediaPause"
        const val RESUME_DELAY_MILLIS = 700L
    }
}
