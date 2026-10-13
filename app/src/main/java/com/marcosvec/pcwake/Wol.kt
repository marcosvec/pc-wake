package com.marcosvec.pcwake

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN burst: same packet to subnet broadcast, global broadcast and the PC directly. */
object Wol {
    fun burst(mac: String, broadcast: String, ip: String, port: Int = 9, rounds: Int = 5, gapMs: Long = 600) {
        val packet = magicPacket(mac)
        val targets = listOf(broadcast, "255.255.255.255", ip).map { InetAddress.getByName(it) }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(rounds) {
                for (t in targets) socket.send(DatagramPacket(packet, packet.size, t, port))
                Thread.sleep(gapMs)
            }
        }
    }

    /** 6 x 0xFF followed by the MAC repeated 16 times = 102 bytes. */
    fun magicPacket(mac: String): ByteArray {
        val m = mac.split(":", "-").map { it.toInt(16).toByte() }
        require(m.size == 6) { "bad MAC: $mac" }
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) for (j in 0 until 6) out[6 + r * 6 + j] = m[j]
        return out
    }
}