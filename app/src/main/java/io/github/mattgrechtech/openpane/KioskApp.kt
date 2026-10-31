package io.github.mattgrechtech.openpane

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import io.github.mattgrechtech.openpane.control.KioskController
import io.github.mattgrechtech.openpane.control.KioskState
import io.github.mattgrechtech.openpane.control.UpdateCheck
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.diag.CrashGuard
import io.github.mattgrechtech.openpane.service.KioskService
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import io.github.mattgrechtech.openpane.settings.createSettings
import io.github.mattgrechtech.openpane.web.HaAuth
import io.github.mattgrechtech.openpane.web.HaApi
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
    lateinit var planner: io.github.mattgrechtech.openpane.web.Planner
        private set
    lateinit var phoneSignIn: io.github.mattgrechtech.openpane.web.PhoneSignIn
        private set
    val selfUpdate by lazy { io.github.mattgrechtech.openpane.control.SelfUpdate(this) }
    /** Created on first use: nothing about voice exists until someone switches it on and asks. */
    val voice by lazy {
        io.github.mattgrechtech.openpane.web.Voice(this, settings, auth) { title, message, done ->
            val popup = org.json.JSONObject().put("id", "assist").put("title", title).put("message", message)
                .put("duration", if (done) 8 else 15).put("priority", "high")
            android.os.Handler(android.os.Looper.getMainLooper()).post { controller.execute("notify", popup) }
        }
    }

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
        val ha = HaApi(settings, auth)
        planner = io.github.mattgrechtech.openpane.web.Planner(settings, ha)
        phoneSignIn = io.github.mattgrechtech.openpane.web.PhoneSignIn(settings, auth)
        controller.eventListeners += { type, data -> ha.fire(type, data) }
        controller.cameraStream = ha::cameraStream
        controller.haState = ha::state
        controller.haService = ha::callService
        KioskService.start(this)
        applyHomeScreen()
        scheduleUpdateCheck()
        io.github.mattgrechtech.openpane.ui.TvUi.highContrast = settings[Keys.UI_HIGH_CONTRAST]
        // Turning a control channel on or off starts or stops the service.
        settings.addListener { changed ->
            if (Keys.UI_HIGH_CONTRAST.name in changed) io.github.mattgrechtech.openpane.ui.TvUi.highContrast = settings[Keys.UI_HIGH_CONTRAST]
            if (changed.any { it in SERVICE_KEYS }) KioskService.start(this)
            if (Keys.HOME_APP.name in changed) applyHomeScreen()
        }
    }

    private val updates = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastUpdateCheck = 0L

    /** Asks GitHub once a day, and only while the setting is on. */
    private fun scheduleUpdateCheck() {
        updates.scheduleWithFixedDelay({
            if (!settings[Keys.CHECK_UPDATES]) {
                lastUpdateCheck = 0
                if (state.latestVersion != null) state.update { latest(null, null) }
                return@scheduleWithFixedDelay
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheck < UpdateCheck.INTERVAL_MILLIS) return@scheduleWithFixedDelay
            lastUpdateCheck = now
            UpdateCheck.latest()?.let { release ->
                if (UpdateCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    AppLog.i("KioskApp", "OpenPane ${release.version} is out")
                }
                state.update { latest(release.version, release.url) }
            }
        }, 1, 60, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** Offers the dashboard as a home screen, or stops offering it. The user then picks it once. */
    private fun applyHomeScreen() {
        val state = if (settings[Keys.HOME_APP]) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // The alias's class name uses the code's namespace, which debug builds don't change.
        val alias = ComponentName(packageName, KioskApp::class.java.name.substringBeforeLast('.') + ".HomeScreen")
        runCatching { packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP) }
            .onFailure { AppLog.w("KioskApp", "Can't change the home screen setting", it) }
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
