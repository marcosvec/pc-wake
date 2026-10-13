package com.marcosvec.pcwake

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile

/**
 * TV screen: live relay status, recent activity, a local Wake button
 * (same burst-and-confirm logic as the relay) and a Test relay checklist.
 */
class MainActivity : Activity() {

    private var cfg: Config? = null
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var panel: TextView
    private lateinit var activity: TextView
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val refresher = object : Runnable {
        override fun run() { refreshPanel(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(64, 40, 64, 40)
        }
        root.addView(text("PC Wake", 34f, Color.WHITE, center = true))
        root.addView(text(cfg?.let { "Relay v${RelayService.VERSION}   target ${it.pcIp}" }
            ?: "Not configured: push files/config.json over ADB", 16f, Color.rgb(170, 170, 170), center = true)
            .apply { setPadding(0, 8, 0, 24) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val wakeButton = button("Wake PC") { wake() }
        buttons.addView(wakeButton)
        buttons.addView(button("Test relay") { test() })
        root.addView(buttons)

        status = text("", 20f, Color.WHITE, center = true).apply { setPadding(0, 20, 0, 8) }
        results = text("", 16f, Color.rgb(220, 220, 220), mono = true)
        panel = text("", 16f, Color.rgb(220, 220, 220), mono = true)
        activity = text("", 14f, Color.rgb(150, 150, 150), mono = true)
        root.addView(status)
        root.addView(results)
        root.addView(header("Relay"))
        root.addView(panel)
        root.addView(header("Recent activity (newest first)"))
        root.addView(activity)

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 18, 18)); addView(root) })
        wakeButton.requestFocus()
        RelayService.start(this, "app-opened")
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { ui.removeCallbacks(refresher); super.onPause() }

    // ---- live panel ------------------------------------------------------------

    private fun refreshPanel() {
        val now = System.currentTimeMillis()
        val s = RelayState
        val relay = if (RelayService.running)
            "running, up ${dur(now - s.startedAt)}" + (if (s.waking) ", WAKING PC NOW" else "")
        else "NOT RUNNING"
        val mailbox = when {
            cfg == null -> "not configured"
            s.authProblem != null -> "PROBLEM: ${s.authProblem}"
            s.pollError != null -> "PROBLEM: ${s.pollError}"
            s.lastPollAt == 0L -> "not checked yet"
            else -> "checked ${ago(now - s.lastPollAt)} (every 5s)"
        }
        val pc = when (s.pcAwake) { null -> "not checked yet"; true -> "awake"; false -> "asleep" } +
            (if (s.pcCheckedAt > 0) ", checked ${ago(now - s.pcCheckedAt)}" else "")
        val last = s.lastResult?.let { "$it, ${ago(now - s.lastResultAt)}" } ?: "none since relay start"
        val page = if (s.lastStatusWriteAt > 0) "updated ${ago(now - s.lastStatusWriteAt)}" else "not updated yet"
        panel.text = listOf(
            row("Relay", relay), row("Mailbox", mailbox), row("PC", pc),
            row("Last command", last), row("Phone page", page)
        ).joinToString("\n")
        val events = tailLines(File(filesDir, "heartbeat.log")).filter { !it.contains(" HB #") }.takeLast(8).reversed()
        activity.text = if (events.isEmpty()) "nothing in the last few hours" else events.joinToString("\n")
    }

    // ---- buttons -------------------------------------------------------------

    private fun wake() {
        val c = cfg ?: return
        if (busy) return
        busy = true
        results.text = ""
        status.text = "Sending wake burst..."
        Thread {
            try {
                val r = Waker.wakeAndConfirm(c) { n -> if (n > 1) post { status.text = "No answer yet, burst $n..." } }
                post { status.text = if (r.awake) "PC is awake (${r.secs}s)" else "No answer after ${r.secs}s" }
            } catch (e: Exception) {
                post { status.text = "Error: ${e.javaClass.simpleName}: ${e.message}" }
            } finally {
                busy = false
            }
        }.start()
    }

    /** Checks every link in the chain except the wake itself, so it never wakes the PC. */
    private fun test() {
        if (busy) return
        busy = true
        status.text = "Testing relay..."
        results.text = ""
        Thread {
            val out = mutableListOf<String>()
            var fails = 0
            fun check(name: String, ok: Boolean, detail: String) {
                if (!ok) fails++
                out += "${if (ok) "OK  " else "FAIL"}  ${name.padEnd(13)} $detail"
            }
            try {
                val c = cfg
                check("Config", c != null, if (c != null) "loaded" else "missing, push files/config.json over ADB")

                val wasRunning = RelayService.running
                if (!wasRunning) RelayService.start(this@MainActivity, "test-button")
                check("Relay service", wasRunning, if (wasRunning) "running" else "was stopped, restarted it")

                val cm = getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val net = when {
                    caps == null -> null
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "connected"
                }
                check("Network", net != null, net ?: "no network")

                if (c != null) {
                    val t0 = System.currentTimeMillis()
                    try {
                        Gist(c).fetchCommand()
                        check("Mailbox", true, "reachable, token OK (${System.currentTimeMillis() - t0} ms)")
                    } catch (e: GistHttpException) {
                        check("Mailbox", false, when (e.code) {
                            401 -> "token expired or revoked"
                            404 -> "gist not found, or token lost gist access"
                            else -> "GitHub error ${e.code}"
                        })
                    } catch (e: Exception) {
                        check("Mailbox", false, "can't reach GitHub (${e.javaClass.simpleName})")
                    }
                }

                val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                check("Power saving", exempt, if (exempt) "exempt" else "not exempt, run the deviceidle whitelist command")

                val w = RelayState.lastStatusWriteAt
                val fresh = w > 0 && System.currentTimeMillis() - w < 20 * 60_000L
                check("Phone page", fresh, if (w > 0) "updated ${ago(System.currentTimeMillis() - w)}" else "not updated yet")

                if (c != null) {
                    val awake = Net.isAwake(c.pcIp)
                    out += "INFO  ${"PC".padEnd(13)} ${if (awake) "awake" else "asleep or unreachable"} at ${c.pcIp}"
                }
            } catch (e: Exception) {
                fails++
                out += "FAIL  test crashed: ${e.javaClass.simpleName}: ${e.message}"
            }
            post {
                status.text = if (fails == 0) "All checks passed" else "$fails problem${if (fails > 1) "s" else ""} found"
                results.text = out.joinToString("\n")
                busy = false
            }
        }.start()
    }

    // ---- helpers -------------------------------------------------------------

    private fun post(r: () -> Unit) { ui.post(r) }

    private fun text(s: String, size: Float, color: Int, center: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = if (center) Gravity.CENTER else Gravity.START
            if (mono) typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun header(s: String) = text(s, 18f, Color.rgb(120, 170, 255)).apply { setPadding(0, 28, 0, 6) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(16, 0, 16, 0) }
    }

    private fun row(k: String, v: String) = k.padEnd(14) + v

    private fun ago(ms: Long): String {
        val sec = maxOf(0L, ms / 1000)
        return when { sec < 60 -> "${sec}s ago"; sec < 3600 -> "${sec / 60} min ago"; else -> "${sec / 3600} h ago" }
    }

    private fun dur(ms: Long): String { val m = maxOf(0L, ms / 60_000); return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m" }

    /** Last few KB of the log, so the 2 s refresh never reads the whole file. */
    private fun tailLines(f: File, bytes: Int = 16_384): List<String> = try {
        RandomAccessFile(f, "r").use { r ->
            val start = maxOf(0L, r.length() - bytes)
            r.seek(start)
            val buf = ByteArray((r.length() - start).toInt())
            r.readFully(buf)
            String(buf).lines().drop(if (start > 0) 1 else 0).filter { it.isNotBlank() }
        }
    } catch (e: Exception) { emptyList() }
}