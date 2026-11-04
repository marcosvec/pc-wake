package com.marcosvec.pcwake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleFilesTest {
    @Test fun exampleConfigIsValidAndPlaceholderOnly() {
        val text = repoFile("config.example.json").readText()
        val cfg = Config.parse(text)
        assertEquals("github_pat_replace_me", cfg.token)
        assertEquals("AA:BB:CC:DD:EE:FF", cfg.pcMac)
        assertEquals("192.168.1.50", cfg.pcIp)
        assertNoRealToken(text)
    }

    @Test fun envExampleIsPlaceholdersOnly() {
        val text = repoFile(".env.example").readText()
        assertTrue(text.contains("github_pat_replace_me"))
        assertTrue(text.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(text.contains("SIGNING_SECRET="))
        assertNoRealToken(text)
        assertFalse(text.contains("BEGIN "))
    }

    private fun assertNoRealToken(text: String) {
        assertFalse(Regex("""github_pat_[A-Za-z0-9_]{20,}""").containsMatchIn(text))
        assertFalse(Regex("""ghp_[A-Za-z0-9]{20,}""").containsMatchIn(text))
    }

    private fun repoFile(name: String): File {
        val candidates = listOf(File(name), File("..", name), File("../..", name))
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $name from ${File(".").canonicalPath}")
    }
}
