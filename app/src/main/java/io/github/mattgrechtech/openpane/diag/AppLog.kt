package io.github.mattgrechtech.openpane.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's own log: what happened on this TV, kept so a tester can send it. Goes to logcat as
 * usual, to a small ring in memory, and to files in the app's private storage (two files of up
 * to 512 KB). Tokens and passwords are blanked before anything is stored.
 */
object AppLog {

    private const val MAX_MEMORY_LINES = 500
    private const val MAX_FILE_BYTES = 512 * 1024
    private val lines = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
    }

    fun d(tag: String, message: String) = log('D', tag, message, null)
    fun i(tag: String, message: String) = log('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log('E', tag, message, error)

    /** The most recent lines, oldest first. */
    fun recent(count: Int = 200): List<String> = synchronized(lines) { lines.toList().takeLast(count) }

    /** Everything in the log files, oldest first (for export). */
    fun fileContents(): String {
        val current = file ?: return recent().joinToString("\n")
        val previous = File(current.path + ".1")
        return buildString {
            if (previous.exists()) append(previous.readText())
            if (current.exists()) append(current.readText())
        }
    }

    private fun log(level: Char, tag: String, message: String, error: Throwable?) {
        val text = redact(message) + (error?.let { " | " + redact(summary(it)) } ?: "")
        when (level) {
            'D' -> Log.d(tag, text)
            'I' -> Log.i(tag, text)
            'W' -> Log.w(tag, text)
            else -> Log.e(tag, text)
        }
        val line = "${synchronized(time) { time.format(Date()) }} $level/$tag: $text"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
        }
        val target = file ?: return
        writer.execute {
            try {
                if (target.length() > MAX_FILE_BYTES) target.renameTo(File(target.path + ".1"))
                target.appendText(line + "\n")
            } catch (e: Exception) {
                // A full disk must not break the app.
            }
        }
    }

    /** Exception class, message and the first few stack frames, on one line. */
    fun summary(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message} @ " +
            error.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private val SECRET_PATTERNS = listOf(
        Regex("(?i)(token|access_token|refresh_token|password|code)=([^&\\s\"]+)") to "$1=…",
        // JSON fields such as "mqtt_password":"…" or "settings_pin":"1234".
        Regex("(?i)(\"[\\w-]*(token|password|pin|secret)\"\\s*:\\s*)\"[^\"]*\"") to "$1\"…\"",
        Regex("(?i)(Bearer\\s+)\\S+") to "$1…",
        Regex("\\b[0-9a-f]{32,}\\b") to "…",
        Regex("\\beyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+") to "…", // JWTs (HA access tokens)
    )

    /** Blanks tokens, passwords and long hex secrets. */
    fun redact(text: String): String = SECRET_PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
}
