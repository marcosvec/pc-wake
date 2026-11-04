package com.marcosvec.pcwake

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN burst: same packet to subnet broadcast, global broadcast and the PC directly. */
object Wol {
    val DEFAULT_PORTS = intArrayOf(9, 7)

    fun burst(
        mac: String,
        broadcast: String,
        ip: String,
        ports: IntArray = DEFAULT_PORTS,
        rounds: Int = 5,
        gapMs: Long = 600,
        prepare: (DatagramSocket) -> Unit = {},
    ) {
        val packet = magicPacket(mac)
        val targets = listOf(broadcast, "255.255.255.255", ip).distinct().map { InetAddress.getByName(it) }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            try {
                prepare(socket)
            } catch (_: Exception) {
            }
            var sent = 0
            var lastError: Exception? = null
            repeat(rounds) { round ->
                for (target in targets) {
                    for (port in ports) {
                        try {
                            socket.send(DatagramPacket(packet.copyOf(), packet.size, target, port))
                            sent++
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                }
                if (gapMs > 0 && round != rounds - 1) Thread.sleep(gapMs)
            }
            if (sent == 0) throw lastError ?: IOException("no magic packets sent")
        }
    }

    /** 6 x 0xFF followed by the MAC repeated 16 times = 102 bytes. */
    fun magicPacket(mac: String): ByteArray {
        val parts = mac.split(':', '-')
        if (parts.size != 6) throw IllegalArgumentException("bad MAC: $mac")
        val m = parts.map { part ->
            val n = part.toIntOrNull(16) ?: throw IllegalArgumentException("bad MAC: $mac")
            if (n !in 0..255) throw IllegalArgumentException("bad MAC: $mac")
            n.toByte()
        }
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) for (j in 0 until 6) out[6 + r * 6 + j] = m[j]
        return out
    }
}
