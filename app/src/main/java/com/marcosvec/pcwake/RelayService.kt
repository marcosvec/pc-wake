package com.marcosvec.pcwake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Always-on relay.
 *  - every 5 s: check the gist for a new signed command; valid wake -> burst -> confirm -> report
 *  - every 60 s: heartbeat line (survival test)
 *  - every 5 min: status.json so the control page can show "relay last seen"
 */
class RelayService : Service() {

    companion object {
        const val TAG = "PCWake"
        const val CHANNEL = "relay"
        const val NOTIF_ID = 1
        const val POLL_MS = 5_000L
        const val HEARTBEAT_MS = 60_000L
        const val STATUS_EVERY_BEATS = 5
        const val MAX_SKEW_MS = 120_000L
        const val CONFIRM_TIMEOUT_MS = 45_000L

        fun start(ctx: Context, reason: String) {
            ctx.startForegroundService(Intent(ctx, RelayService::class.java).putExtra("reason", reason))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var loop: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var gist: Gist? = null
    private var cfg: Config? = null
    private var beats = 0L
    private var pollErrors = 0
    private var lastNonce: String? = null
    private var lastResult: JSONObject? = null
    private var pcAwake = false
    private val started = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PC Wake relay", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, Notification.Builder(this, CHANNEL)
            .setContentTitle("PC Wake relay running")
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setOngoing(true).build())

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcwake:relay").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pcwake:wifi").apply { acquire() }

        lastNonce = getSharedPreferences("relay", MODE_PRIVATE).getString("lastNonce", null)
        cfg = Config.load(this)
        gist = cfg?.let { Gist(it) }

        thread = HandlerThread("relay").apply { start() }
        loop = Handler(thread.looper)
        log("SERVICE CREATED v0.3 config=${if (cfg != null) "ok" else "MISSING"}")
        loop.post(::heartbeat)
        loop.post(::poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("START reason=${intent?.getStringExtra("reason") ?: "restart-by-system"}")
        return START_STICKY
    }

    // ---- loops -------------------------------------------------------------

    private fun heartbeat() {
        beats++
        pcAwake = (cfg?.let { Net.isAwake(it.pcIp) } ?: false)
        val upMin = (System.currentTimeMillis() - started) / 60_000
        log("HB #$beats up=${upMin}m pc=${if (pcAwake) "awake" else "asleep"} pollErrors=$pollErrors")
        if (beats == 1L || beats % STATUS_EVERY_BEATS == 0L) reportStatus()
        loop.postDelayed(::heartbeat, HEARTBEAT_MS)
    }

    private fun poll() {
        try {
            gist?.fetchCommand()?.let { handle(it) }
            pollErrors = 0
        } catch (e: Exception) {
            pollErrors++
            if (pollErrors == 1 || pollErrors % 12 == 0) log("POLL ERROR #$pollErrors ${e.javaClass.simpleName}: ${e.message}")
        }
        // back off while the network is failing
        loop.postDelayed(::poll, if (pollErrors > 3) 30_000L else POLL_MS)
    }
    // ---- command handling ----------------------------------------------------

    private fun handle(content: String) {
        val cmd = JSONObject(content).optJSONObject("cmd") ?: return
        val nonce = cmd.optString("nonce")
        if (nonce.isEmpty() || nonce == lastNonce) return
        rememberNonce(nonce)

        val action = cmd.optString("action")
        val ts = cmd.optLong("ts")
        val sig = cmd.optString("sig").lowercase()
        val expected = hmacHex(cfg!!.secret, "$action|$ts|$nonce")

        val reject = when {
            !MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray()) -> "rejected: bad signature"
            abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS -> "rejected: stale command"
            action != "wake" -> "rejected: unknown action"
            else -> null
        }
        if (reject != null) { finish(nonce, reject, null); return }

        if ((cfg?.let { Net.isAwake(it.pcIp) } ?: false)) { finish(nonce, "already awake", 0); return }

        log("WAKE nonce=$nonce")
        cfg!!.let { Wol.burst(it.pcMac, it.broadcast, it.pcIp) }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < CONFIRM_TIMEOUT_MS) {
            if ((cfg?.let { Net.isAwake(it.pcIp) } ?: false)) {
                finish(nonce, "awake", (System.currentTimeMillis() - t0) / 1000); return
            }
            Thread.sleep(1000)
        }
        finish(nonce, "no response after ${CONFIRM_TIMEOUT_MS / 1000}s", null)
    }

    private fun finish(nonce: String, result: String, secs: Long?) {
        log("RESULT nonce=$nonce $result" + (if (secs != null) " (${secs}s)" else ""))
        if (result == "awake" || result == "already awake") pcAwake = true
        lastResult = JSONObject().put("nonce", nonce).put("result", result)
            .put("secs", secs ?: JSONObject.NULL).put("at", System.currentTimeMillis())
        reportStatus()
    }

    private fun reportStatus() {
        val g = gist ?: return
        val s = JSONObject()
            .put("relay_seen", System.currentTimeMillis())
            .put("relay_version", "0.3")
            .put("relay_up_min", (System.currentTimeMillis() - started) / 60_000)
            .put("pc", if (pcAwake) "awake" else "asleep")
            .put("last", lastResult ?: JSONObject.NULL)
        try { g.writeStatus(s) } catch (e: Exception) { log("STATUS WRITE ERROR ${e.message}") }
    }

    private fun rememberNonce(n: String) {
        lastNonce = n
        getSharedPreferences("relay", MODE_PRIVATE).edit().putString("lastNonce", n).apply()
    }

    private fun hmacHex(secret: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ---- misc ----------------------------------------------------------------

    private fun log(msg: String) {
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $msg"
        Log.i(TAG, line)
        try {
            val f = File(filesDir, "heartbeat.log")
            if (f.length() > 2_000_000) f.delete()
            f.appendText(line + "\n")
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        log("SERVICE DESTROYED")
        loop.removeCallbacksAndMessages(null)
        thread.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}