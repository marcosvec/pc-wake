package com.marcosvec.pcwake

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Always-on relay.
 *  poll thread       every 5 s: check the gist for a new signed command
 *  wake thread       verified wake: re-burst until the PC answers or 60 s pass
 *  heartbeat thread  every 60 s: check the PC and log; status.json only on change or every 15 min
 *  watchdog alarm    every ~15 min: restarts this service if Fire OS killed it
 */
class RelayService : Service() {

    companion object {
        const val TAG = "PCWake"
        const val VERSION = "0.4"
        const val CHANNEL = "relay"
        const val NOTIF_ID = 1
        const val POLL_MS = 5_000L
        const val POLL_BACKOFF_MS = 30_000L
        const val AUTH_BACKOFF_MS = 5 * 60_000L
        const val HEARTBEAT_MS = 60_000L
        const val KEEPALIVE_MS = 15 * 60_000L
        const val WATCHDOG_MS = 15 * 60_000L
        const val MAX_SKEW_MS = 120_000L

        @Volatile var running = false

        fun start(ctx: Context, reason: String) {
            ctx.startForegroundService(Intent(ctx, RelayService::class.java).putExtra("reason", reason))
        }
    }

    private lateinit var pollThread: HandlerThread
    private lateinit var pollLoop: Handler
    private lateinit var hbThread: HandlerThread
    private lateinit var hbLoop: Handler
    private val wakeExec = Executors.newSingleThreadExecutor()
    private val waking = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var gist: Gist? = null
    private var cfg: Config? = null
    private var beats = 0L
    private var pollErrors = 0
    private var lastNonce: String? = null
    @Volatile private var lastResult: JSONObject? = null
    @Volatile private var pcAwake = false
    @Volatile private var authProblem: String? = null
    private var lastWrittenKey: String? = null
    private var lastWriteAt = 0L
    private val started = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PC Wake relay", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notification("PC Wake relay running"))

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcwake:relay").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pcwake:wifi").apply { acquire() }

        lastNonce = getSharedPreferences("relay", MODE_PRIVATE).getString("lastNonce", null)
        cfg = Config.load(this)
        gist = cfg?.let { Gist(it) }
        armWatchdog()

        pollThread = HandlerThread("poll").apply { start() }
        pollLoop = Handler(pollThread.looper)
        hbThread = HandlerThread("heartbeat").apply { start() }
        hbLoop = Handler(hbThread.looper)
        log("SERVICE CREATED v$VERSION config=${if (cfg != null) "ok" else "MISSING"}")
        hbLoop.post(::heartbeat)
        pollLoop.post(::poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("START reason=${intent?.getStringExtra("reason") ?: "restart-by-system"}")
        return START_STICKY
    }

    // ---- loops -------------------------------------------------------------

    private fun heartbeat() {
        beats++
        pcAwake = cfg?.let { Net.isAwake(it.pcIp) } ?: false
        val upMin = (System.currentTimeMillis() - started) / 60_000
        log("HB #$beats up=${upMin}m pc=${if (pcAwake) "awake" else "asleep"} pollErrors=$pollErrors" +
            (authProblem?.let { " AUTH=$it" } ?: ""))
        reportStatus(force = false)
        hbLoop.postDelayed(::heartbeat, HEARTBEAT_MS)
    }

    private fun poll() {
        var next = POLL_MS
        try {
            gist?.fetchCommand()?.let { handle(it) }
            if (pollErrors > 0) log("POLL OK again after $pollErrors errors")
            pollErrors = 0
            if (authProblem != null) {
                log("AUTH OK again")
                authProblem = null
                setNotification("PC Wake relay running")
                reportStatus(force = true)
            }
        } catch (e: GistHttpException) {
            pollErrors++
            val problem = when (e.code) {
                401 -> "token expired or revoked"
                404 -> "gist not found, or token lost gist access"
                else -> null
            }
            if (problem != null) {
                if (authProblem != problem) {
                    authProblem = problem
                    log("AUTH PROBLEM: $problem")
                    setNotification("PC Wake: $problem")
                }
                next = AUTH_BACKOFF_MS
            } else {
                logPollError(e)
                next = if (pollErrors > 3) POLL_BACKOFF_MS else POLL_MS
            }
        } catch (e: Exception) {
            pollErrors++
            logPollError(e)
            next = if (pollErrors > 3) POLL_BACKOFF_MS else POLL_MS
        }
        pollLoop.postDelayed(::poll, next)
    }

    private fun logPollError(e: Exception) {
        if (pollErrors == 1 || pollErrors % 12 == 0) log("POLL ERROR #$pollErrors ${e.javaClass.simpleName}: ${e.message}")
    }

    // ---- command handling ----------------------------------------------------

    /** Runs on the poll thread. Verification is fast; the wake itself goes to the wake thread. */
    private fun handle(content: String) {
        val cmd = try { JSONObject(content).optJSONObject("cmd") } catch (e: Exception) { null } ?: return
        val nonce = cmd.optString("nonce")
        if (nonce.isEmpty() || nonce == lastNonce) return
        rememberNonce(nonce)
        val c = cfg ?: return

        val action = cmd.optString("action")
        val ts = cmd.optLong("ts")
        val sig = cmd.optString("sig").lowercase()
        val expected = hmacHex(c.secret, "$action|$ts|$nonce")

        val reject = when {
            !MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray()) -> "rejected: bad signature"
            abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS -> "rejected: stale command"
            action != "wake" -> "rejected: unknown action"
            else -> null
        }
        if (reject != null) { finish(nonce, reject, null); return }
        if (!waking.compareAndSet(false, true)) { finish(nonce, "wake already in progress", null); return }

        wakeExec.execute {
            try {
                if (Net.isAwake(c.pcIp)) {
                    finish(nonce, "already awake", 0)
                } else {
                    log("WAKE nonce=$nonce")
                    val r = Waker.wakeAndConfirm(c) { n -> if (n > 1) log("RE-BURST #$n nonce=$nonce") }
                    if (r.awake) finish(nonce, "awake", r.secs)
                    else finish(nonce, "no response after ${r.secs}s (${r.bursts} bursts)", null)
                }
            } catch (e: Exception) {
                finish(nonce, "error: ${e.javaClass.simpleName}", null)
            } finally {
                waking.set(false)
            }
        }
    }

    private fun finish(nonce: String, result: String, secs: Long?) {
        log("RESULT nonce=$nonce $result" + (if (secs != null) " (${secs}s)" else ""))
        if (result == "awake" || result == "already awake") pcAwake = true
        lastResult = JSONObject().put("nonce", nonce).put("result", result)
            .put("secs", secs ?: JSONObject.NULL).put("at", System.currentTimeMillis())
        reportStatus(force = true)
    }

    /** Writes status.json only when something changed, or every 15 min as a keepalive. */
    @Synchronized private fun reportStatus(force: Boolean) {
        val g = gist ?: return
        if (authProblem != null) return
        val pc = if (pcAwake) "awake" else "asleep"
        val key = "$pc|${lastResult?.optString("nonce")}"
        val now = System.currentTimeMillis()
        if (!force && key == lastWrittenKey && now - lastWriteAt < KEEPALIVE_MS) return
        val s = JSONObject()
            .put("relay_seen", now)
            .put("relay_version", VERSION)
            .put("relay_up_min", (now - started) / 60_000)
            .put("keepalive_min", KEEPALIVE_MS / 60_000)
            .put("pc", pc)
            .put("last", lastResult ?: JSONObject.NULL)
        try {
            g.writeStatus(s)
            lastWrittenKey = key
            lastWriteAt = now
        } catch (e: Exception) { log("STATUS WRITE ERROR ${e.message}") }
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

    private fun armWatchdog() {
        val pi = PendingIntent.getBroadcast(this, 0, Intent(this, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WATCHDOG_MS, WATCHDOG_MS, pi)
    }

    private fun notification(title: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle(title)
        .setSmallIcon(android.R.drawable.ic_lock_power_off)
        .setOngoing(true).build()

    private fun setNotification(title: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title))
    }

    @Synchronized private fun log(msg: String) {
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $msg"
        Log.i(TAG, line)
        try {
            val f = File(filesDir, "heartbeat.log")
            if (f.length() > 1_000_000) f.renameTo(File(filesDir, "heartbeat.old.log"))
            f.appendText(line + "\n")
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        running = false
        log("SERVICE DESTROYED")
        pollLoop.removeCallbacksAndMessages(null)
        hbLoop.removeCallbacksAndMessages(null)
        pollThread.quitSafely()
        hbThread.quitSafely()
        wakeExec.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}