package com.nirhib.boiler

import android.content.Context
import android.net.wifi.WifiManager
import com.nirhib.boiler.core.SwitcherProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Finds Switcher water heaters on the Wi-Fi: they broadcast their details (incl. id and key) every few seconds. */
object Discovery {
    fun find(context: Context, timeoutMs: Long = 10_000): List<SwitcherProtocol.Discovered> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("boiler-discovery").apply { setReferenceCounted(false); acquire() }
        val found = java.util.concurrent.ConcurrentHashMap<String, SwitcherProtocol.Discovered>()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            val listeners = SwitcherProtocol.UDP_PORTS.map { port ->
                thread(name = "discovery-$port") {
                    runCatching {
                        DatagramSocket(null).use { sock ->
                            sock.reuseAddress = true; sock.broadcast = true
                            sock.bind(InetSocketAddress(port))
                            val buf = ByteArray(1024)
                            while (System.currentTimeMillis() < deadline && found.isEmpty()) {
                                sock.soTimeout = maxOf(1, (deadline - System.currentTimeMillis()).toInt())
                                val pkt = DatagramPacket(buf, buf.size)
                                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
                                SwitcherProtocol.parseDiscovery(buf.copyOf(pkt.length), pkt.address.hostAddress ?: continue)
                                    ?.let { found[it.id] = it }
                            }
                            // after the first hit, listen ~3 s more in case there is more than one switch
                            val extraUntil = minOf(deadline, System.currentTimeMillis() + 3000)
                            while (found.isNotEmpty() && System.currentTimeMillis() < extraUntil) {
                                sock.soTimeout = maxOf(1, (extraUntil - System.currentTimeMillis()).toInt())
                                val pkt = DatagramPacket(buf, buf.size)
                                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
                                SwitcherProtocol.parseDiscovery(buf.copyOf(pkt.length), pkt.address.hostAddress ?: continue)
                                    ?.let { found[it.id] = it }
                            }
                        }
                    }
                }
            }
            listeners.forEach { it.join(timeoutMs + 4000) }
        } finally {
            lock.release()
        }
        return found.values.toList()
    }
}
