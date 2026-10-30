package io.github.mattgrechtech.tvkiosk

import android.app.Application
import android.os.Build
import io.github.mattgrechtech.tvkiosk.control.KioskController
import io.github.mattgrechtech.tvkiosk.control.KioskState
import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.diag.CrashGuard
import io.github.mattgrechtech.tvkiosk.service.KioskService
import io.github.mattgrechtech.tvkiosk.settings.Keys
import io.github.mattgrechtech.tvkiosk.settings.Settings
import io.github.mattgrechtech.tvkiosk.settings.createSettings
import io.github.mattgrechtech.tvkiosk.web.HaAuth
import io.github.mattgrechtech.tvkiosk.web.HaEvents
import java.security.SecureRandom

class KioskApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var state: KioskState
        private set
    lateinit var controller: KioskController
        private set
    lateinit var auth: HaAuth
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashGuard.install(this)
        AppLog.i("KioskApp", "Started ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        settings = createSettings(this)
        ensureIdentity(settings)
        state = KioskState()
        controller = KioskController(this, settings, state)
        auth = HaAuth(settings)
        val events = HaEvents(settings, auth)
        controller.actionListeners += { data -> events.fire(HaEvents.ACTION_EVENT, data) }
        KioskService.start(this)
        // Turning a control channel on or off starts or stops the service.
        settings.addListener { changed ->
            if (changed.any { it in SERVICE_KEYS }) KioskService.start(this)
        }
    }

    companion object {
        private val SERVICE_KEYS = setOf(Keys.API_ENABLED.name, Keys.TVOVERLAY_COMPAT.name, Keys.MQTT_ENABLED.name)

        /** A new random token: 32 bytes as hex. */
        fun newToken(bytes: Int = 32): String {
            val buffer = ByteArray(bytes)
            SecureRandom().nextBytes(buffer)
            return buffer.joinToString("") { "%02x".format(it) }
        }

        private fun ensureIdentity(settings: Settings) {
            settings.edit {
                if (settings[Keys.DEVICE_ID].isEmpty()) put(Keys.DEVICE_ID, newToken(4))
                if (settings[Keys.API_TOKEN].isEmpty()) put(Keys.API_TOKEN, newToken())
                if (settings[Keys.DEVICE_NAME].isEmpty()) put(Keys.DEVICE_NAME, Build.MODEL ?: "TV")
            }
        }
    }
}
