package com.marcosvec.pcwake

import android.content.Context
import java.io.File

/** Reads files/config.json pushed over ADB. The file stays on the device. */
object DeviceConfig {
    private fun file(ctx: Context) = File(ctx.filesDir, "config.json")

    fun load(ctx: Context): Config? = try {
        Config.parse(file(ctx).readText())
    } catch (_: Exception) {
        null
    }

    /** Null when the file is present and valid. */
    fun problem(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return "missing, push files/config.json over ADB"
        return try {
            Config.parse(f.readText())
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "invalid config"
        } catch (_: Exception) {
            "invalid config"
        }
    }
}
