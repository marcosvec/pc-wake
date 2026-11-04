package com.marcosvec.pcwake

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GistTest {
    private val server = MockWebServer()
    private lateinit var gist: Gist

    @Before fun start() {
        server.start()
        gist = Gist(
            Config(
                token = "github_pat_test",
                secret = "replace-with-a-long-random-string",
                gistId = "abc123def456",
                pcMac = "AA:BB:CC:DD:EE:FF",
                pcIp = "192.168.1.50",
                broadcast = "192.168.1.255",
            ),
            server.url("/").toString().trimEnd('/'),
        )
    }

    @After fun stop() {
        gist.close()
        server.shutdown()
    }

    @Test fun fetchUsesTheTokenAndCachesA304() {
        server.enqueue(MockResponse().setBody(gistBody("{\"cmd\":null}")).addHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals("{\"cmd\":null}", gist.fetchCommand())
        assertEquals("{\"cmd\":null}", gist.fetchCommand())
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/gists/abc123def456", first.path)
        assertEquals("Bearer github_pat_test", first.getHeader("Authorization"))
        assertEquals("pc-wake-relay", first.getHeader("User-Agent"))
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
    }

    @Test fun missingCommandIsNull() {
        server.enqueue(MockResponse().setBody("""{"files":{}}"""))
        assertNull(gist.fetchCommand())
    }

    @Test fun unauthorizedIncludesTheGitHubMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        try {
            gist.fetchCommand()
            fail("expected 401")
        } catch (e: GistHttpException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("Bad credentials"))
        }
    }

    @Test fun statusAndClearAreJsonPatches() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        gist.writeStatus(JSONObject().put("pc", "asleep").put("relay_seen", 10))
        gist.clearCommand()
        val status = server.takeRequest()
        val clear = server.takeRequest()
        assertEquals("PATCH", status.method)
        assertTrue(status.getHeader("Content-Type")!!.startsWith("application/json"))
        val statusJson = JSONObject(status.body.readUtf8())
        val content = statusJson.getJSONObject("files").getJSONObject("status.json").getString("content")
        assertTrue(content.contains("\"pc\": \"asleep\""))
        val cleared = JSONObject(clear.body.readUtf8())
            .getJSONObject("files").getJSONObject("command.json").getString("content")
        assertEquals("""{"cmd":null}""", cleared)
    }

    private fun gistBody(command: String) =
        JSONObject().put("files", JSONObject().put("command.json", JSONObject().put("content", command))).toString()
}
