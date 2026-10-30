package io.github.mattgrechtech.tvkiosk.mqtt

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import io.github.mattgrechtech.tvkiosk.diag.AppLog

/**
 * Connects to the MQTT broker, announces the TV to Home Assistant through discovery, publishes
 * state and turns command messages into controller commands.
 */
class MqttBridge(
    private val config: Config,
    private val discovery: Discovery,
    /** Runs a controller command; called on a worker thread. */
    private val execute: (String, JSONObject) -> Unit,
    /** The current state document; called on a worker thread. */
    private val stateJson: () -> JSONObject,
) {
    class Config(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val user: String,
        val password: String,
        val clientId: String,
    )

    /** "connecting", "connected", "disconnected" or an error message; shown in settings. */
    @Volatile var status: String = "stopped"
        private set

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt").apply { isDaemon = true }
    }
    private var client: MqttClient? = null
    private var pendingState: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    private var retryDelaySeconds = 5L

    fun start() {
        scheduler.execute { connect() }
        // Memory, uptime and friends change without events: refresh every minute.
        scheduler.scheduleWithFixedDelay({ publishState() }, 60, 60, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        scheduler.execute {
            client?.let {
                runCatching { it.publish(discovery.availabilityTopic, Discovery.OFFLINE.toByteArray(), 1, true) }
                runCatching { it.disconnect(2000) }
                runCatching { it.close() }
            }
            client = null
            status = "stopped"
        }
        scheduler.shutdown()
    }

    /** Coalesces bursts of state changes into one publish a second later. */
    fun stateChanged() {
        if (stopped) return
        synchronized(this) {
            if (pendingState?.isDone == false) return
            pendingState = runCatching { scheduler.schedule(Runnable { publishState() }, 1, TimeUnit.SECONDS) }.getOrNull()
        }
    }

    /** A pop-up button press, for the MQTT event entity. */
    fun publishEvent(data: JSONObject) {
        if (stopped) return
        scheduler.execute {
            val mqtt = client ?: return@execute
            if (!mqtt.isConnected) return@execute
            val payload = JSONObject(data.toString()).put("event_type", Discovery.EVENT_PRESSED)
            runCatching { mqtt.publish(discovery.eventTopic, payload.toString().toByteArray(Charsets.UTF_8), 1, false) }
                .onFailure { AppLog.w(TAG, "MQTT event publish failed", it) }
        }
    }

    private fun connect() {
        if (stopped) return
        status = "connecting"
        val scheme = if (config.tls) "ssl" else "tcp"
        try {
            val mqtt = MqttClient("$scheme://${config.host}:${config.port}", config.clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (config.user.isNotEmpty()) userName = config.user
                if (config.password.isNotEmpty()) password = config.password.toCharArray()
                setWill(discovery.availabilityTopic, Discovery.OFFLINE.toByteArray(), 1, true)
            }
            mqtt.setCallback(Callback())
            mqtt.connect(options)
            client = mqtt
            retryDelaySeconds = 5
        } catch (e: MqttException) {
            status = "can't connect: ${e.message}"
            AppLog.w(TAG, "MQTT connect failed (${e.reasonCode}); retrying in ${retryDelaySeconds}s")
            if (!stopped) {
                // Paho's automatic reconnect only starts after a first success.
                runCatching { scheduler.schedule(Runnable { connect() }, retryDelaySeconds, TimeUnit.SECONDS) }
                retryDelaySeconds = (retryDelaySeconds * 2).coerceAtMost(300)
            }
        }
    }

    private inner class Callback : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            status = "connected"
            runCatching { scheduler.execute { announce() } }
        }

        override fun connectionLost(cause: Throwable?) {
            status = "disconnected: ${cause?.message ?: "unknown"}"
            AppLog.w(TAG, "MQTT connection lost", cause)
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val name = discovery.commandName(topic) ?: return
            val payload = String(message.payload, Charsets.UTF_8)
            if (message.isRetained) return // Never replay stale commands.
            val command = Discovery.toCommand(name, payload)
            if (command == null) {
                AppLog.w(TAG, "Ignored MQTT command $name")
                return
            }
            runCatching { scheduler.execute {
                try {
                    execute(command.first, command.second)
                } catch (e: Exception) {
                    AppLog.e(TAG, "MQTT command ${command.first} failed", e)
                }
            } }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private fun announce() {
        val mqtt = client ?: return
        try {
            mqtt.subscribe(discovery.commandFilter, 1)
            for ((topic, payload) in discovery.messages()) {
                mqtt.publish(topic, payload.toString().toByteArray(Charsets.UTF_8), 1, true)
            }
            mqtt.publish(discovery.availabilityTopic, Discovery.ONLINE.toByteArray(), 1, true)
            publishState()
        } catch (e: MqttException) {
            AppLog.w(TAG, "MQTT announce failed", e)
        }
    }

    private fun publishState() {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        try {
            mqtt.publish(discovery.stateTopic, stateJson().toString().toByteArray(Charsets.UTF_8), 0, true)
        } catch (e: Exception) {
            AppLog.w(TAG, "MQTT state publish failed", e)
        }
    }

    private companion object {
        const val TAG = "MqttBridge"
    }
}
