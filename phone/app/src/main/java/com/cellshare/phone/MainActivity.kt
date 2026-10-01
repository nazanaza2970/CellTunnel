package com.cellshare.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var listAdapter: ArrayAdapter<DeviceTracker.Device>? = null
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<EditText>(R.id.editPassword).setText(
            getSharedPreferences(TunnelService.PREFS, MODE_PRIVATE)
                .getString(TunnelService.KEY_PASSWORD, "") ?: ""
        )
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val pw = findViewById<EditText>(R.id.editPassword).text.toString()
            getSharedPreferences(TunnelService.PREFS, MODE_PRIVATE)
                .edit().putString(TunnelService.KEY_PASSWORD, pw).apply()
            findViewById<TextView>(R.id.status).text =
                if (pw.isEmpty()) "Password removed — open access" else "Password saved"
        }
        findViewById<Button>(R.id.btnToggle).setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            DeviceTracker.clear()
            refresh()
        }

        listAdapter = object : ArrayAdapter<DeviceTracker.Device>(this, R.layout.item_device, R.id.deviceText) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_device, parent, false)
                val d = getItem(position)!!
                val age = (System.currentTimeMillis() - d.lastSeen) / 1000
                val target = if (d.lastTarget.isEmpty()) "No active target" else "→ ${d.lastTarget}"

                v.findViewById<TextView>(R.id.deviceAddress).text = d.address
                v.findViewById<TextView>(R.id.deviceConnBadge).text = "${d.activeConns} conn"
                v.findViewById<TextView>(R.id.deviceAge).text = "${age}s ago"
                v.findViewById<TextView>(R.id.deviceBytesOut).text = human(d.bytesOut)
                v.findViewById<TextView>(R.id.deviceBytesIn).text = human(d.bytesIn)
                v.findViewById<TextView>(R.id.deviceTarget).text = target

                return v
            }
        }
        findViewById<ListView>(R.id.deviceList).adapter = listAdapter
    }

    override fun onResume() {
        super.onResume()
        running = TunnelService.running
        updateButtons()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun toggle() {
        val intent = Intent(this, TunnelService::class.java)
        if (running) intent.action = TunnelService.ACTION_STOP else intent.action = null
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        running = !running
        updateButtons()
    }

    private fun updateButtons() {
        val btn = findViewById<Button>(R.id.btnToggle)
        val statusDot = findViewById<View>(R.id.statusDot)
        val statusHeader = findViewById<TextView>(R.id.statusHeader)
        val statusText = findViewById<TextView>(R.id.status)

        if (running) {
            btn.text = "Stop tunnel"
            btn.setBackgroundResource(R.drawable.bg_btn_stop)
            statusDot?.setBackgroundResource(R.drawable.bg_status_dot_active)
            statusHeader?.text = "TUNNEL ACTIVE"
            statusText?.text = "Listening on port ${TunnelService.PORT} — connect laptop via WiFi"
        } else {
            btn.text = "Start tunnel"
            btn.setBackgroundResource(R.drawable.bg_btn_primary)
            statusDot?.setBackgroundResource(R.drawable.bg_status_dot_inactive)
            statusHeader?.text = "TUNNEL STOPPED"
            statusText?.text = "Tap below to start SOCKS5 tunnel"
        }
    }

    private fun refresh() {
        listAdapter?.clear()
        listAdapter?.addAll(DeviceTracker.snapshot())
        listAdapter?.notifyDataSetChanged()
    }

    private fun human(b: Long): String = when {
        b >= 1_073_741_824 -> String.format("%.1f GB", b / 1_073_741_824.0)
        b >= 1_048_576 -> String.format("%.1f MB", b / 1_048_576.0)
        b >= 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> "$b B"
    }
}
