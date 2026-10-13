package com.marcosvec.pcwake

import java.net.InetSocketAddress
import java.net.Socket

/** Shared network checks. Awake = answers ping, or accepts TCP on SMB/RPC. */
object Net {
    fun isAwake(ip: String): Boolean = ping(ip) || tcpOpen(ip, 445) || tcpOpen(ip, 135)

    fun ping(ip: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "1", ip))
        p.waitFor() == 0
    } catch (e: Exception) { false }

    fun tcpOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 700) }
        true
    } catch (e: Exception) { false }
}