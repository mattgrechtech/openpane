package io.github.mattgrechtech.openpane.diag

import android.content.Context
import io.github.mattgrechtech.openpane.KioskApp
import io.github.mattgrechtech.openpane.settings.SettingsJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * One bundle for a bug report: state, settings (no secrets), the last crash and recent log lines.
 * Safe to share: secrets are left out and the log is redacted.
 */
object Diagnostics {

    fun collect(context: Context, logLines: Int = 300): JSONObject {
        val app = context.applicationContext as KioskApp
        val json = JSONObject()
            .put("generated_at", Instant.now().toString())
            .put("state", app.state.toJson(context))
            .put("settings", SettingsJson.export(app.settings, includeSecrets = false))
            .put("log", JSONArray(AppLog.recent(logLines)))
        CrashGuard.lastCrash(context)?.let { crash ->
            json.put("last_crash", JSONObject()
                .put("at", Instant.ofEpochMilli(crash.atMillis).toString())
                .put("summary", crash.summary)
                .put("details", AppLog.redact(crash.details.take(8000))))
        }
        return json
    }

    /** Writes the bundle where adb can fetch it; returns the file. */
    fun export(context: Context): File {
        val file = File(context.getExternalFilesDir(null), "diagnostics.json")
        file.writeText(collect(context, logLines = 500).toString(2))
        return file
    }
}
