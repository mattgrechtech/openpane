package io.github.mattgrechtech.tvkiosk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mattgrechtech.tvkiosk.BuildConfig
import io.github.mattgrechtech.tvkiosk.KioskApp
import io.github.mattgrechtech.tvkiosk.R
import io.github.mattgrechtech.tvkiosk.api.AdminPage
import io.github.mattgrechtech.tvkiosk.api.ApiHandler
import io.github.mattgrechtech.tvkiosk.api.HttpServer
import io.github.mattgrechtech.tvkiosk.api.TvOverlayCompatHandler
import io.github.mattgrechtech.tvkiosk.control.KioskState
import io.github.mattgrechtech.tvkiosk.mqtt.Discovery
import io.github.mattgrechtech.tvkiosk.mqtt.MqttBridge
import io.github.mattgrechtech.tvkiosk.settings.Keys
import io.github.mattgrechtech.tvkiosk.settings.SettingsJson
import io.github.mattgrechtech.tvkiosk.web.KioskActivity
import java.io.IOException
import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.diag.Diagnostics

/**
 * Keeps the control channels running while other apps are on screen: the REST API, the
 * TvOverlay-compatible endpoint and MQTT. A foreground service, so Android doesn't stop it.
 */
class KioskService : Service() {

    private lateinit var app: KioskApp
    private val main = Handler(Looper.getMainLooper())
    private var api: HttpServer? = null
    private var compat: HttpServer? = null
    private var mqtt: MqttBridge? = null
    private val adminPage by lazy { AdminPage(this) }
    private val announcer by lazy { Announcer(this) }

    private val settingsListener: (Set<String>) -> Unit = { changed ->
        if (changed.any { it in RESTART_KEYS }) {
            main.removeCallbacks(restart)
            main.postDelayed(restart, 500)
        }
    }
    private val stateListener: () -> Unit = { mqtt?.stateChanged() }
    private val eventListener: (String, org.json.JSONObject) -> Unit = { type, data -> mqtt?.publishEvent(type, data) }
    private val restart = Runnable {
        stopChannels()
        startChannels()
    }

    override fun onCreate() {
        super.onCreate()
        app = application as KioskApp
        goForeground()
        app.settings.addListener(settingsListener)
        app.state.addListener(stateListener)
        startChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), even when the
        // service is already running, or Android kills the app a few seconds later.
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        app.settings.removeListener(settingsListener)
        app.state.removeListener(stateListener)
        main.removeCallbacksAndMessages(null)
        stopChannels()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun allowedHosts(): Set<String> =
        (KioskState.localIpv4Addresses() + listOf("localhost", "127.0.0.1")).toSet()

    private fun startChannels() {
        val settings = app.settings
        val controller = app.controller

        if (settings[Keys.API_ENABLED]) {
            val handler = ApiHandler(
                token = { settings[Keys.API_TOKEN] },
                allowedHosts = ::allowedHosts,
                execute = { name, args -> controller.executeBlocking(name, args) },
                exportSettings = { SettingsJson.export(settings, includeSecrets = false) },
                importSettings = { json -> SettingsJson.import(settings, json).toJson() },
                diagnostics = { Diagnostics.collect(this) },
                settingsSchema = { SettingsJson.schema() },
                staticFile = adminPage::file,
            )
            api = startServer(settings[Keys.API_PORT], handler, "API")
            if (api != null) announcer.start(settings[Keys.API_PORT], settings[Keys.DEVICE_ID], settings[Keys.DEVICE_NAME])
        }

        if (settings[Keys.TVOVERLAY_COMPAT]) {
            val handler = TvOverlayCompatHandler(::allowedHosts) { args -> controller.executeBlocking("notify", args) }
            compat = startServer(settings[Keys.TVOVERLAY_PORT], handler, "TvOverlay-compatible")
        }

        if (settings[Keys.MQTT_ENABLED] && settings[Keys.MQTT_HOST].isNotEmpty()) {
            val deviceId = settings[Keys.DEVICE_ID]
            val discovery = Discovery(
                baseTopic = settings[Keys.MQTT_BASE_TOPIC],
                discoveryPrefix = settings[Keys.MQTT_DISCOVERY_PREFIX],
                deviceId = deviceId,
                deviceName = settings[Keys.DEVICE_NAME],
                model = Build.MODEL ?: "Android TV",
                manufacturer = Build.MANUFACTURER ?: "Android",
                appVersion = BuildConfig.VERSION_NAME,
            )
            val config = MqttBridge.Config(
                host = settings[Keys.MQTT_HOST],
                port = settings[Keys.MQTT_PORT],
                tls = settings[Keys.MQTT_TLS],
                user = settings[Keys.MQTT_USER],
                password = settings[Keys.MQTT_PASSWORD],
                clientId = "tvkiosk-$deviceId",
            )
            mqtt = MqttBridge(
                config,
                discovery,
                execute = { name, args -> controller.executeBlocking(name, args) },
                stateJson = { app.state.toJson(this) },
            ).also { it.start() }
            controller.eventListeners -= eventListener
            controller.eventListeners += eventListener
        }
    }

    private fun startServer(port: Int, handler: (HttpServer.Request) -> HttpServer.Response, label: String): HttpServer? {
        val server = HttpServer(port, handler)
        return try {
            server.start()
            AppLog.i(TAG, "$label listening on port $port")
            server
        } catch (e: IOException) {
            AppLog.e(TAG, "$label can't listen on port $port", e)
            app.state.update { error("$label can't use port $port: ${e.message}") }
            null
        }
    }

    private fun stopChannels() {
        announcer.stop()
        api?.stop()
        api = null
        compat?.stop()
        compat = null
        mqtt?.stop()
        mqtt = null
        app.controller.eventListeners -= eventListener
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, KioskActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "KioskService"
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1

        private val RESTART_KEYS = setOf(
            Keys.API_ENABLED, Keys.API_PORT, Keys.TVOVERLAY_COMPAT, Keys.TVOVERLAY_PORT,
            Keys.MQTT_ENABLED, Keys.MQTT_HOST, Keys.MQTT_PORT, Keys.MQTT_TLS, Keys.MQTT_USER, Keys.MQTT_PASSWORD,
            Keys.MQTT_BASE_TOPIC, Keys.MQTT_DISCOVERY_PREFIX, Keys.DEVICE_NAME,
        ).map { it.name }.toSet()

        /** Starts the service if any control channel is on. Safe to call repeatedly. */
        fun start(context: Context) {
            val app = context.applicationContext as KioskApp
            val s = app.settings
            val wanted = s[Keys.API_ENABLED] || s[Keys.TVOVERLAY_COMPAT] || s[Keys.MQTT_ENABLED]
            val intent = Intent(context, KioskService::class.java)
            try {
                when {
                    !wanted -> context.stopService(intent)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> context.startForegroundService(intent)
                    else -> context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ refuses to start a foreground service from the background (on 15,
                // even with "display over other apps"); the dashboard starts it on resume instead.
                AppLog.w(TAG, "Can't start the service now: ${e.message}")
            }
        }
    }
}
