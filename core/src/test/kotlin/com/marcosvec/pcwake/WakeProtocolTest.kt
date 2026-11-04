package com.marcosvec.pcwake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeProtocolTest {
    private val now = 1_700_000_000_000L

    @Test fun hmacMatchesTheControlPageVector() {
        val msg = WakeProtocol.message("wake", now, "0011223344556677")
        assertEquals("wake|1700000000000|0011223344556677", msg)
        assertEquals(
            "c49d1a2c5bdccdfb81db4776cf092efad64de81531b6cb4278cc866750e4c0e2",
            WakeProtocol.hmacHex("secret", msg),
        )
    }

    @Test fun hmacUsesUtf8() {
        assertEquals(
            "a09fe18709bb105b5e1e8d2e7d81cc2bacbe8f18f4595e3fb513af7a19c18883",
            WakeProtocol.hmacHex("påss", WakeProtocol.message("wake", 1, "abcdefgh")),
        )
    }

    @Test fun wireCommandFromThePageIsAccepted() {
        // JSON.stringify(buildWakeCommand(...)) for the vector above. Shared with docs/protocol.test.js.
        val wire = """{"cmd":{"action":"wake","ts":1700000000000,"nonce":"0011223344556677","sig":"c49d1a2c5bdccdfb81db4776cf092efad64de81531b6cb4278cc866750e4c0e2"}}"""
        val d = WakeProtocol.decide(wire, "secret", now, NonceStore())
        assertTrue(d is WakeProtocol.Decision.Accept)
    }

    @Test fun uppercaseSignatureIsAccepted() {
        val content = signed("secret", "wake", now, "0011223344556677").replace(
            "c49d1a2c5bdccdfb81db4776cf092efad64de81531b6cb4278cc866750e4c0e2",
            "C49D1A2C5BDCCDFB81DB4776CF092EFAD64DE81531B6CB4278CC866750E4C0E2",
        )
        val d = WakeProtocol.decide(content, "secret", now, NonceStore())
        assertTrue(d is WakeProtocol.Decision.Accept)
    }

    @Test fun badSignatureIsRejectedOnce() {
        val store = NonceStore()
        val content = signed("secret", "wake", now, "0011223344556677").replace("c49d", "ffff")
        val first = WakeProtocol.decide(content, "secret", now, store)
        assertTrue(first is WakeProtocol.Decision.Reject)
        assertEquals("rejected: bad signature", (first as WakeProtocol.Decision.Reject).reason)
        assertTrue(WakeProtocol.decide(content, "secret", now, store) is WakeProtocol.Decision.Ignore)
    }

    @Test fun wrongSecretIsRejected() {
        val content = signed("secret", "wake", now, "0011223344556677")
        val d = WakeProtocol.decide(content, "other-secret", now, NonceStore())
        assertTrue(d is WakeProtocol.Decision.Reject)
        assertEquals("rejected: bad signature", (d as WakeProtocol.Decision.Reject).reason)
    }

    @Test fun staleAndFutureCommandsAreRejected() {
        val store = NonceStore()
        val old = WakeProtocol.decide(signed("secret", "wake", now - 121_000, "aabbccddeeff0011"), "secret", now, store)
        val future = WakeProtocol.decide(signed("secret", "wake", now + 121_000, "ffeeddccbbaa9988"), "secret", now, store)
        assertEquals("rejected: stale command", (old as WakeProtocol.Decision.Reject).reason)
        assertEquals("rejected: stale command", (future as WakeProtocol.Decision.Reject).reason)
    }

    @Test fun unknownActionIsRejected() {
        val d = WakeProtocol.decide(signed("secret", "sleep", now, "abc123xyz"), "secret", now, NonceStore())
        assertTrue(d is WakeProtocol.Decision.Reject)
        assertEquals("rejected: unknown action", (d as WakeProtocol.Decision.Reject).reason)
    }

    @Test fun replayOfAnOlderNonceStaysRejected() {
        val store = NonceStore()
        val first = signed("secret", "wake", now, "0011223344556677")
        val second = signed("secret", "wake", now, "8899aabbccddeeff")
        assertTrue(WakeProtocol.decide(first, "secret", now, store) is WakeProtocol.Decision.Accept)
        assertTrue(WakeProtocol.decide(second, "secret", now, store) is WakeProtocol.Decision.Accept)
        assertTrue(WakeProtocol.decide(first, "secret", now, store) is WakeProtocol.Decision.Ignore)
    }

    @Test fun nonceStoreSurvivesARestart() {
        val store = NonceStore()
        val content = signed("secret", "wake", now, "0011223344556677")
        WakeProtocol.decide(content, "secret", now, store)
        val restored = NonceStore.from(store.serialize())
        assertTrue(WakeProtocol.decide(content, "secret", now, restored) is WakeProtocol.Decision.Ignore)
    }

    @Test fun emptyAndUnsignedCommandsAreIgnored() {
        val store = NonceStore()
        assertTrue(WakeProtocol.decide("{\"cmd\":null}", "secret", now, store) is WakeProtocol.Decision.Ignore)
        assertTrue(WakeProtocol.decide("not json", "secret", now, store) is WakeProtocol.Decision.Ignore)
        assertTrue(WakeProtocol.decide("", "secret", now, store) is WakeProtocol.Decision.Ignore)
        val later = WakeProtocol.decide(signed("secret", "wake", now, "0011223344556677"), "secret", now, store)
        assertTrue(later is WakeProtocol.Decision.Accept)
    }

    @Test fun shortNonceDoesNotEvictARealOne() {
        val store = NonceStore(cap = 2)
        val kept = signed("secret", "wake", now, "0011223344556677")
        WakeProtocol.decide(kept, "secret", now, store)
        val tiny = signed("secret", "wake", now, "abcd")
        assertTrue(WakeProtocol.decide(tiny, "secret", now, store) is WakeProtocol.Decision.Ignore)
        assertTrue(store.seen("0011223344556677"))
        assertFalse(store.seen("abcd"))
    }

    @Test fun nonceStoreDropsTheOldest() {
        val store = NonceStore(cap = 2)
        store.remember("one11111")
        store.remember("two22222")
        store.remember("three333")
        assertFalse(store.seen("one11111"))
        assertTrue(store.seen("two22222"))
        assertTrue(store.seen("three333"))
        assertEquals("two22222,three333", store.serialize())
    }

    private fun signed(secret: String, action: String, ts: Long, nonce: String): String {
        val sig = WakeProtocol.hmacHex(secret, WakeProtocol.message(action, ts, nonce))
        return """{"cmd":{"action":"$action","ts":$ts,"nonce":"$nonce","sig":"$sig"}}"""
    }
}
