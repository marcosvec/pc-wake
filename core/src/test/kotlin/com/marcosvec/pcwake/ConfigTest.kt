package com.marcosvec.pcwake

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ConfigTest {
    @Test fun parsesADashMacAndTrims() {
        val cfg = Config.parse(
            """
            {
              "token": " github_pat_example ",
              "secret": "replace-with-a-long-random-string",
              "gist_id": "0123456789abcdef0123456789abcdef",
              "pc_mac": "AA-BB-CC-DD-EE-FF",
              "pc_ip": "192.168.1.50",
              "broadcast": "192.168.1.255"
            }
            """.trimIndent()
        )
        assertEquals("github_pat_example", cfg.token)
        assertEquals("AA-BB-CC-DD-EE-FF", cfg.pcMac)
        assertEquals("192.168.1.50", cfg.pcIp)
    }

    @Test fun rejectsMissingAndNonsenseFields() {
        expect("config missing token") { Config.parse(valid().replace("github_pat_example", " ")) }
        expect("bad MAC") { Config.parse(valid().replace("AA:BB:CC:DD:EE:FF", "not-a-mac")) }
        expect("bad PC IP") { Config.parse(valid().replace("192.168.1.50", "192.168.1")) }
        expect("bad broadcast") { Config.parse(valid().replace("192.168.1.255", "192.168.1.256")) }
        expect("bad gist id") { Config.parse(valid().replace("0123456789abcdef0123456789abcdef", "nope")) }
        expect("invalid config.json") { Config.parse("{") }
    }

    private fun expect(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected $message")
        } catch (e: IllegalArgumentException) {
            assertEquals(message, e.message)
        }
    }

    private fun valid() = """
        {
          "token": "github_pat_example",
          "secret": "replace-with-a-long-random-string",
          "gist_id": "0123456789abcdef0123456789abcdef",
          "pc_mac": "AA:BB:CC:DD:EE:FF",
          "pc_ip": "192.168.1.50",
          "broadcast": "192.168.1.255"
        }
    """.trimIndent()
}
