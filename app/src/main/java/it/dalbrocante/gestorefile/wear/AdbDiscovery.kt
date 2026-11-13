package it.dalbrocante.gestorefile.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/** Trova l'orologio sulla Wi-Fi tramite mDNS (stesso metodo di "adb"), così non servono IP e porta. */
object AdbDiscovery {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"

    /** Ritorna IP e porta del primo servizio trovato (o di quello con IP [preferHost], se compare). */
    suspend fun find(ctx: Context, type: String, timeoutMs: Long = 8000, preferHost: String? = null): Pair<String, Int>? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val h = r.host
                            if (h is Inet4Address) h.hostAddress?.let { found.trySend(it to r.port) }
                        }
                    })
                }
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        var first: Pair<String, Int>? = null
        var res: Pair<String, Int>? = null
        withTimeoutOrNull(timeoutMs) {
            while (res == null) {
                val p = found.receive()
                if (first == null) first = p
                if (preferHost == null || p.first == preferHost) res = p
            }
        }
        runCatching { nsd.stopServiceDiscovery(listener) }
        return res ?: first
    }
}
