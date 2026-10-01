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
                val target = if (d.lastTarget.isEmpty()) "-" else d.lastTarget
                v.findViewById<TextView>(R.id.deviceText).text =
                    "${d.address}  ·  ${d.activeConns} conn  ·  ${age}s ago\n" +
                    "up ${human(d.bytesOut)}  ·  down ${human(d.bytesIn)}  ·  → $target"
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
        if (running) intent.action = Intent.ACTION_STOP else intent.action = null
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        running = !running
        updateButtons()
    }

    private fun updateButtons() {
        val btn = findViewById<Button>(R.id.btnToggle)
        btn.text = if (running) "Stop tunnel" else "Start tunnel"
        if (running) {
            findViewById<TextView>(R.id.status).text =
                "Listening on port ${TunnelService.PORT} — connect your laptop via WiFi"
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
