package io.github.mattgrechtech.openpane.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.github.mattgrechtech.openpane.diag.AppLog
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds Home Assistant servers on the local network: HA announces itself over mDNS as
 * `_home-assistant._tcp`, with its name and URLs in the TXT record. Results arrive on the main
 * thread as a growing list.
 */
class HaDiscovery(context: Context, private val onChange: (List<Server>) -> Unit) {

    data class Server(val name: String, val url: String, val version: String)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, Server>()
    // Before Android 14 only one resolve may run at a time, so they queue.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Discovery didn't start ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't search the network: ${e.message}")
            listener = null
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        toResolve.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun queue(info: NsdServiceInfo) {
        if (listener == null) return
        toResolve.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService is the API available back to Android 10.
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val txt = serviceInfo.attributes.mapValues { (_, bytes) -> bytes?.toString(Charsets.UTF_8) }
                val url = urlFrom(txt, hostText(serviceInfo.host), serviceInfo.port)
                val server = url?.let {
                    Server(txt["location_name"].orEmpty().ifEmpty { serviceInfo.serviceName }, it, txt["version"].orEmpty())
                }
                main.post {
                    resolving = false
                    if (server != null && listener != null) {
                        // One server can announce itself more than once (IPv4 and IPv6).
                        found[txt["uuid"].orEmpty().ifEmpty { server.url }] = server
                        onChange(found.values.toList())
                    }
                    resolveNext()
                }
            }
        })
    }

    private fun hostText(address: InetAddress?): String? = when (address) {
        null -> null
        is Inet4Address -> address.hostAddress
        else -> address.hostAddress?.substringBefore('%')?.let { "[$it]" }
    }

    companion object {
        private const val TAG = "HaDiscovery"
        const val SERVICE_TYPE = "_home-assistant._tcp"

        /**
         * The best URL for a server from its TXT record: the internal URL HA was told about,
         * then its base URL, then the address and port it answered from. Only http(s) URLs count,
         * and a `.local` name loses to the address, because not every TV can look those up.
         */
        fun urlFrom(txt: Map<String, String?>, host: String?, port: Int): String? {
            val direct = if (!host.isNullOrEmpty() && port in 1..65535) "http://$host:$port" else null
            for (name in listOf("internal_url", "base_url")) {
                val value = txt[name]?.trim()?.trimEnd('/').orEmpty()
                if (!value.startsWith("http://") && !value.startsWith("https://")) continue
                val hostName = value.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
                if (hostName.endsWith(".local") && direct != null) continue
                return value
            }
            return direct
        }
    }
}
