package com.marcosvec.pcwake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class NetTest {
    @Test fun tcpOpenSeesALocalListener() {
        ServerSocket(0).use { server ->
            val t = Thread {
                try {
                    server.accept().close()
                } catch (_: Exception) {
                }
            }
            t.isDaemon = true
            t.start()
            assertTrue(Net.tcpOpen("127.0.0.1", server.localPort, timeoutMs = 1_000))
            t.join(1_000)
        }
    }

    @Test fun tcpOpenIsFalseWhenNothingListens() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(Net.tcpOpen("127.0.0.1", port, timeoutMs = 300))
    }
}
