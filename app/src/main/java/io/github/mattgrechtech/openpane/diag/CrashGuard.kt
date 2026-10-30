package io.github.mattgrechtech.openpane.diag

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.mattgrechtech.openpane.web.KioskActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * A kiosk must come back by itself. On an uncaught exception this records the crash and asks
 * Android to reopen the dashboard two seconds later, unless the app has crashed [MAX_CRASHES]
 * times in [WINDOW_MILLIS], when it stops so a broken set-up can't loop forever.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val MAX_CRASHES = 3
    private const val WINDOW_MILLIS = 10 * 60 * 1000L
    private const val PREFS = "crashes"

    class Crash(val atMillis: Long, val summary: String, val details: String)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, thread, error)
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't record the crash", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        AppLog.e(TAG, "Crash on thread ${thread.name}", error)
        crashFile(context).writeText("$now\n${AppLog.summary(error)}\n\n$stack")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = (prefs.getString("times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MILLIS } + now
        val giveUp = recent.size > MAX_CRASHES
        prefs.edit()
            .putString("times", recent.joinToString(","))
            .putBoolean("unseen", true)
            .putBoolean("gave_up", giveUp)
            .commit()
        if (!giveUp) scheduleRestart(context)
    }

    private fun scheduleRestart(context: Context) {
        val intent = Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 14+: let the alarm open the activity on the app's behalf (it may draw over apps).
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        val pending = PendingIntent.getActivity(
            context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT, options,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pending)
    }

    /** The last crash, or null if there hasn't been one. */
    fun lastCrash(context: Context): Crash? {
        val file = crashFile(context)
        if (!file.exists()) return null
        val text = file.readText()
        val at = text.substringBefore('\n').toLongOrNull() ?: return null
        val summary = text.substringAfter('\n').substringBefore('\n')
        return Crash(at, summary, text.substringAfter("\n\n", ""))
    }

    /** True once after a crash, so the next start can say the app restarted. */
    fun takeUnseen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unseen = prefs.getBoolean("unseen", false)
        if (unseen) prefs.edit().putBoolean("unseen", false).apply()
        return unseen
    }

    /** True when the app stopped restarting itself after repeated crashes. */
    fun gaveUp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gave_up", false)

    fun clearGaveUp(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gave_up", false).apply()
    }

    private fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")
}
