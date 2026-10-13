package com.marcosvec.pcwake

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TV screen: shows the target, starts the always-on relay, and has a local Wake button
 * that uses the same burst-and-confirm logic as the relay.
 */
class MainActivity : Activity() {

    private var cfg: Config? = null
    private lateinit var status: TextView
    private lateinit var wakeButton: Button
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(64, 64, 64, 64)
        }
        val title = TextView(this).apply {
            text = "PC Wake"
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = cfg?.let { "Relay v${RelayService.VERSION}\nTarget ${it.pcMac}  ${it.pcIp}" }
                ?: "Not configured: push files/config.json over ADB"
            textSize = 16f
            setTextColor(Color.rgb(170, 170, 170))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        wakeButton = Button(this).apply {
            text = "Wake PC"
            textSize = 24f
            setOnClickListener { wake() }
        }
        status = TextView(this).apply {
            text = "Checking PC..."
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(info)
        root.addView(wakeButton)
        root.addView(status)
        setContentView(root)
        wakeButton.requestFocus()

        RelayService.start(this, "app-opened")

        Thread {
            val c = cfg ?: return@Thread
            setStatus(if (Net.isAwake(c.pcIp)) "PC is awake" else "PC looks asleep")
        }.start()
    }

    private fun wake() {
        val c = cfg ?: return
        if (busy) return
        busy = true
        setStatus("Sending wake burst...")
        Thread {
            try {
                val r = Waker.wakeAndConfirm(c) { n -> if (n > 1) setStatus("No answer yet, burst $n...") }
                setStatus(if (r.awake) "PC is awake (${r.secs}s)" else "No answer after ${r.secs}s")
            } catch (e: Exception) {
                setStatus("Error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun setStatus(s: String) { ui.post { status.text = s } }
}