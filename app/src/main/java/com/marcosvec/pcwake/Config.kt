package com.marcosvec.pcwake

import android.content.Context
import org.json.JSONObject
import java.io.File

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
        fun load(ctx: Context): Config? = try {
            val j = JSONObject(File(ctx.filesDir, "config.json").readText())
            Config(
                j.getString("token"), j.getString("secret"), j.getString("gist_id"),
                j.getString("pc_mac"), j.getString("pc_ip"), j.getString("broadcast"),
            )
        } catch (e: Exception) { null }
    }
}