package com.marcosvec.pcwake

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GistHttpException(val code: Int, op: String, detail: String? = null) : RuntimeException(
    buildString {
        append("gist ")
        append(op)
        append(' ')
        append(code)
        if (!detail.isNullOrBlank()) {
            append(": ")
            append(detail)
        }
    }
)

/**
 * The secret gist is the mailbox:
 *   command.json  written by the control page  {"cmd": {action, ts, nonce, sig}}
 *   status.json   written by this relay        {relay_seen, pc, last: {...}}
 * Polling uses ETags, so "nothing changed" (304) does not count against the rate limit.
 */
class Gist(private val cfg: Config, apiRoot: String = "https://api.github.com") {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val url = "${apiRoot.trimEnd('/')}/gists/${cfg.gistId}"
    private var etag: String? = null
    private var lastCommand: String? = null

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun base() = Request.Builder().url(url)
        .header("Authorization", "Bearer ${cfg.token}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "pc-wake-relay")

    /** Returns command.json content (cached on 304). Throws on network/auth errors. */
    fun fetchCommand(): String? {
        val b = base()
        etag?.let { b.header("If-None-Match", it) }
        http.newCall(b.get().build()).execute().use { r ->
            if (r.code == 304) return lastCommand
            val raw = r.body?.string()
            if (!r.isSuccessful) throw GistHttpException(r.code, "GET", githubMessage(raw))
            etag = r.header("ETag")
            lastCommand = commandContent(raw)
            return lastCommand
        }
    }

    fun writeStatus(status: JSONObject) {
        patch(JSONObject().put("status.json", JSONObject().put("content", status.toString(2))))
    }

    fun clearCommand() {
        patch(JSONObject().put("command.json", JSONObject().put("content", """{"cmd":null}""")))
    }

    private fun patch(files: JSONObject) {
        val body = JSONObject().put("files", files).toString()
        val req = base().patch(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw GistHttpException(r.code, "PATCH", githubMessage(raw))
            // The gist changed, so the previous ETag must not hide a newer command.
            etag = null
        }
    }

    private fun commandContent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val files = JSONObject(raw).optJSONObject("files") ?: return null
        val file = files.optJSONObject("command.json") ?: return null
        if (!file.has("content") || file.isNull("content")) return null
        return file.optString("content", "").ifEmpty { null }
    }
}

internal fun githubMessage(raw: String?): String? = try {
    if (raw.isNullOrBlank()) null
    else {
        val j = JSONObject(raw)
        if (!j.has("message") || j.isNull("message")) null
        else j.optString("message").trim().take(160).ifBlank { null }
    }
} catch (e: Exception) {
    null
}
