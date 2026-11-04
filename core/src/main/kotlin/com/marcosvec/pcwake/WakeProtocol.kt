package com.marcosvec.pcwake

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Signed wake commands. The signed string is `action|timestamp|nonce` (HMAC-SHA256, hex).
 * docs/protocol.js produces the same bytes; keep the two together.
 */
object WakeProtocol {
    const val MAX_SKEW_MS = 120_000L

    data class SignedCommand(val action: String, val ts: Long, val nonce: String, val sig: String)

    sealed interface Decision {
        data class Accept(val nonce: String) : Decision
        data class Reject(val nonce: String, val reason: String) : Decision
        data object Ignore : Decision
    }

    fun message(action: String, ts: Long, nonce: String) = "$action|$ts|$nonce"

    fun hmacHex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun validNonce(nonce: String) = nonce.matches(Regex("""[A-Za-z0-9_-]{8,64}"""))

    /** Remembers any nonce it accepts or rejects, so a later copy of that command is ignored. */
    fun decide(content: String, secret: String, now: Long, nonces: NonceStore, maxSkewMs: Long = MAX_SKEW_MS): Decision {
        val cmd = parse(content) ?: return Decision.Ignore
        if (!validNonce(cmd.nonce) || nonces.seen(cmd.nonce)) return Decision.Ignore
        nonces.remember(cmd.nonce)
        return when (val verdict = verify(cmd, secret, now, maxSkewMs)) {
            Verdict.Accept -> Decision.Accept(cmd.nonce)
            is Verdict.Reject -> Decision.Reject(cmd.nonce, verdict.reason)
        }
    }

    fun parse(content: String): SignedCommand? {
        return try {
            val root = JSONObject(content)
            if (!root.has("cmd") || root.isNull("cmd")) return null
            val cmd = root.optJSONObject("cmd") ?: return null
            SignedCommand(
                action = text(cmd, "action"),
                ts = if (cmd.has("ts") && !cmd.isNull("ts")) cmd.optLong("ts") else 0L,
                nonce = text(cmd, "nonce"),
                sig = text(cmd, "sig"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun verify(cmd: SignedCommand, secret: String, now: Long, maxSkewMs: Long): Verdict {
        val expected = hmacHex(secret, message(cmd.action, cmd.ts, cmd.nonce))
        val sig = cmd.sig.lowercase(Locale.US)
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), sig.toByteArray(Charsets.UTF_8))) {
            return Verdict.Reject("rejected: bad signature")
        }
        if (abs(now - cmd.ts) > maxSkewMs) return Verdict.Reject("rejected: stale command")
        if (cmd.action != "wake") return Verdict.Reject("rejected: unknown action")
        return Verdict.Accept
    }

    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key, "")
    }

    private sealed interface Verdict {
        data object Accept : Verdict
        data class Reject(val reason: String) : Verdict
    }
}

/** Bounded set of nonces the relay has already handled. Oldest entries fall off the end. */
class NonceStore(private val cap: Int = 64) {
    private val items = ArrayDeque<String>()
    private val set = HashSet<String>()

    fun seen(nonce: String) = nonce in set

    fun remember(nonce: String) {
        if (nonce in set) return
        items.addLast(nonce)
        set.add(nonce)
        while (items.size > cap) set.remove(items.removeFirst())
    }

    fun serialize(): String = items.joinToString(",")

    companion object {
        fun from(saved: String?): NonceStore {
            val store = NonceStore()
            if (saved.isNullOrBlank()) return store
            saved.split(',').forEach { if (it.isNotEmpty()) store.remember(it) }
            return store
        }
    }
}
