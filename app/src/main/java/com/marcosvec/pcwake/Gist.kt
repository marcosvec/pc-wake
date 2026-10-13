package com.marcosvec.pcwake

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GistHttpException(val code: Int, op: String) : RuntimeException("gist $op $code")

/**
 * The secret gist is the mailbox:
 *   command.json  written by the control page  {"cmd": {action, ts, nonce, sig}}
 *   status.json   written by this relay        {relay_seen, pc, last: {...}}
 * Polling uses ETags, so "nothing changed" (304) does not count against the rate limit.
 */
class Gist(private val cfg: Config) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val url = "https://api.github.com/gists/${cfg.gistId}"
    private var etag: String? = null
    private var lastCommand: String? = null

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
            if (!r.isSuccessful) throw GistHttpException(r.code, "GET")
            etag = r.header("ETag")
            val files = JSONObject(r.body!!.string()).getJSONObject("files")
            lastCommand = files.optJSONObject("command.json")?.optString("content")
            return lastCommand
        }
    }

    fun writeStatus(status: JSONObject) {
        val body = JSONObject().put("files", JSONObject().put("status.json",
            JSONObject().put("content", status.toString(2)))).toString()
        val req = base().patch(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw GistHttpException(r.code, "PATCH") }
    }
}