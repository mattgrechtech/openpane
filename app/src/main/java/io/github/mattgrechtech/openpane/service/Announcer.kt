package io.github.mattgrechtech.openpane.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.mattgrechtech.openpane.BuildConfig
import io.github.mattgrechtech.openpane.diag.AppLog

/**
 * Announces the API on the network (mDNS `_openpane._tcp`), so Home Assistant's OpenPane
 * integration finds the TV by itself. Only the device id, name and version are announced;
 * everything else still needs the token.
 */
class Announcer(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int, deviceId: String, name: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "OpenPane $name".take(60)
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("name", name.take(60))
            setAttribute("version", BuildConfig.VERSION_NAME)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i(TAG, "Announced on the network as ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = AppLog.w(TAG, "Can't announce on the network ($error)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            listener = registration
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't announce on the network", e)
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "Announcer"
        const val SERVICE_TYPE = "_openpane._tcp"
    }
}
