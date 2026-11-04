package com.marcosvec.pcwake

import org.json.JSONObject

/**
 * Everything personal lives ONLY on the device, in files/config.json (pushed over ADB),
 * never in this repo:
 * {"token": "...", "secret": "...", "gist_id": "...", "pc_mac": "AA:BB:...", "pc_ip": "192.168.x.x", "broadcast": "192.168.x.255"}
 */
data class Config(
    val token: String,
    val secret: String,
    val gistId: String,
    val pcMac: String,
    val pcIp: String,
    val broadcast: String,
) {
    companion object {
        private val MAC = Regex("""(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}""")
        private val GIST = Regex("""[0-9A-Fa-f]{8,}""")

        fun parse(text: String): Config {
            val j = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid config.json")
            }
            try {
                val cfg = Config(
                    token = req(j, "token"),
                    secret = req(j, "secret"),
                    gistId = req(j, "gist_id"),
                    pcMac = req(j, "pc_mac"),
                    pcIp = req(j, "pc_ip"),
                    broadcast = req(j, "broadcast"),
                )
                if (!MAC.matches(cfg.pcMac)) throw IllegalArgumentException("bad MAC")
                if (!ipv4(cfg.pcIp)) throw IllegalArgumentException("bad PC IP")
                if (!ipv4(cfg.broadcast)) throw IllegalArgumentException("bad broadcast")
                if (!GIST.matches(cfg.gistId)) throw IllegalArgumentException("bad gist id")
                return cfg
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid config.json")
            }
        }

        private fun req(j: JSONObject, key: String): String {
            if (!j.has(key) || j.isNull(key)) throw IllegalArgumentException("config missing $key")
            val value = j.optString(key, "").trim()
            if (value.isEmpty()) throw IllegalArgumentException("config missing $key")
            return value
        }

        fun ipv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return@all false
                val n = part.toIntOrNull() ?: return@all false
                n in 0..255
            }
        }
    }
}
