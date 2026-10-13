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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * v0.1: one screen, one button. Sends a Wake-on-LAN burst to the PC,
 * then polls until the PC answers (or 30 s pass).
 */
class MainActivity : Activity() {

    companion object {
        const val WOL_PORT = 9

        const val BURST_ROUNDS = 5       // rounds of packets
        const val BURST_GAP_MS = 600L    // gap between rounds (~3 s total)
        const val CONFIRM_TIMEOUT_MS = 30_000L
    }

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
            text = cfg?.let { "Target ${it.pcMac}\n${it.pcIp}  (broadcast ${it.broadcast}:$WOL_PORT)" }
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
            setStatus(if (isAwake()) "PC is awake" else "PC looks asleep (or not answering)")
        }.start()
    }

    private fun wake() {
        val c = cfg ?: return
        if (busy) return
        busy = true
        setStatus("Sending wake burst...")
        Thread {
            try {
                val packet = magicPacket(c.pcMac)
                val targets = listOf(c.broadcast, "255.255.255.255", c.pcIp).map { InetAddress.getByName(it) }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    repeat(BURST_ROUNDS) {
                        for (t in targets) socket.send(DatagramPacket(packet, packet.size, t, WOL_PORT))
                        Thread.sleep(BURST_GAP_MS)
                    }
                }
                setStatus("Burst sent (${BURST_ROUNDS * targets.size} packets). Waiting for PC...")

                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < CONFIRM_TIMEOUT_MS) {
                    if (isAwake()) {
                        val secs = (System.currentTimeMillis() - start) / 1000
                        setStatus("PC is awake ($secs s after burst)")
                        return@Thread
                    }
                    Thread.sleep(1000)
                }
                setStatus("No answer after 30 s. If it slept < 30 s ago, wait and retry.")
            } catch (e: Exception) {
                setStatus("Error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    /** 6 x 0xFF followed by the MAC repeated 16 times = 102 bytes. */
    private fun magicPacket(mac: String): ByteArray {
        val m = mac.split(":", "-").map { it.toInt(16).toByte() }
        require(m.size == 6) { "bad MAC: $mac" }
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) for (j in 0 until 6) out[6 + r * 6 + j] = m[j]
        return out
    }

    /** Awake = answers ping, or accepts a TCP connection on SMB/RPC. */
    private fun isAwake(): Boolean = cfg?.let { Net.isAwake(it.pcIp) } ?: false

    private fun setStatus(s: String) { ui.post { status.text = s } }
}