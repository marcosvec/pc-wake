package com.marcosvec.pcwake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class WolTest {
    @Test fun magicPacketIs102BytesWithTheMacRepeated() {
        val mac = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())
        val packet = Wol.magicPacket("01:23:45:67:89:AB")
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        for (r in 0 until 16) {
            assertArrayEquals(mac, packet.copyOfRange(6 + r * 6, 12 + r * 6))
        }
        assertArrayEquals(packet, Wol.magicPacket("01-23-45-67-89-ab"))
    }

    @Test fun badMacIsRejected() {
        try {
            Wol.magicPacket("nope")
            fail("expected a bad MAC to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bad MAC"))
        }
    }

    @Test fun burstReachesALocalSocket() {
        DatagramSocket(null).use { server ->
            server.reuseAddress = true
            server.soTimeout = 2_000
            server.bind(InetSocketAddress("127.0.0.1", 0))
            Wol.burst(
                mac = "AA:BB:CC:DD:EE:FF",
                broadcast = "127.0.0.1",
                ip = "127.0.0.1",
                ports = intArrayOf(server.localPort),
                rounds = 1,
                gapMs = 0,
            )
            val buf = ByteArray(200)
            val pkt = DatagramPacket(buf, buf.size)
            server.receive(pkt)
            assertEquals(102, pkt.length)
            assertArrayEquals(Wol.magicPacket("AA:BB:CC:DD:EE:FF"), buf.copyOf(pkt.length))
        }
    }
}
