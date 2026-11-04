package com.marcosvec.pcwake

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Shared network checks. Awake = answers ping, or accepts TCP on SMB/RPC. */
object Net {
    fun isAwake(ip: String): Boolean = ping(ip) || tcpOpen(ip, 445) || tcpOpen(ip, 135)

    fun ping(ip: String): Boolean = try {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
            .redirectErrorStream(true)
            .start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }

    fun tcpOpen(ip: String, port: Int, timeoutMs: Int = 700): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }
}
