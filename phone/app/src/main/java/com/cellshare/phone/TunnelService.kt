package com.cellshare.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TunnelService : Service() {

    companion object {
        const val PORT = 12345
        const val PREFS = "celltunnel"
        const val KEY_PASSWORD = "password"
        const val ACTION_STOP = "com.cellshare.phone.ACTION_STOP"
        @Volatile var running = false; private set
    }

    private val cm by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var wifiNet: Network? = null
    private var mobileNet: Network? = null
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification())
        registerNetworks()
        startServer()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        server?.runCatching { close() }
        callbacks.forEach { cm.runCatching { unregisterNetworkCallback(it) } }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = NotificationChannel(
            "celltunnel", "CellTunnel", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "celltunnel")
            .setContentTitle("CellTunnel")
            .setContentText("Listening on port $PORT via cellular")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .build()
    }

    private fun registerNetworks() {
        val wifiCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifiNet = network }
        }
        val mobileCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mobileNet = network }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(), mobileCb
        )
        callbacks.add(wifiCb)
        callbacks.add(mobileCb)
    }

    private fun startServer() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(PORT))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val conn = runCatching { s.accept() }.getOrNull() ?: break
                wifiNet?.bindSocket(conn)
                pool.execute { handle(conn) }
            }
        }
    }

    private fun handle(conn: Socket) {
        val remote = conn.remoteSocketAddress as? InetSocketAddress
        val device = DeviceTracker.track(remote?.hostName ?: "unknown")
        try {
            val input = conn.getInputStream()
            val output = conn.getOutputStream()
            val password = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PASSWORD, "") ?: ""
            if (!authenticate(input, output, password)) return
            if (input.read() != 0x05) return
            val cmd = input.read()
            input.read()
            when (cmd) {
                0x01 -> handleConnect(input, output, device)
                0x02 -> handleAssociate(input, output, device)
            }
        } catch (e: Exception) {
            // connection closed
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun handleConnect(input: InputStream, output: OutputStream, device: DeviceTracker.Device) {
        val target = readAddress(input) ?: return
        val s = Socket()
        s.tcpNoDelay = true
        mobileNet?.bindSocket(s)
        s.connect(InetSocketAddress(target.first, target.second), 10_000)
        DeviceTracker.setTarget(device, "${target.first}:${target.second}")
        reply(output, 0)

        val sin = s.getInputStream()
        val sout = s.getOutputStream()
        DeviceTracker.bump(device, 1)
        val done = CountDownLatch(2)
        pool.execute { pump(input, sout, device, true); done.countDown() }
        pool.execute { pump(sin, output, device, false); done.countDown() }
        done.await()
        runCatching { s.close() }
        DeviceTracker.bump(device, -1)
    }

    private fun handleAssociate(input: InputStream, output: OutputStream, device: DeviceTracker.Device) {
        input.read(); input.read()
        // DatagramSocket is not a Socket, so it cannot be bound to the cellular
        // network directly; it uses the default network (best effort for UDP).
        val ds = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        output.write(byteArrayOf(
            0x05, 0x00, 0x00, 0x01,
            0, 0, 0, 0,
            (ds.port shr 8).toByte(), (ds.port and 0xff).toByte()
        ))
        output.flush()
        DeviceTracker.setTarget(device, "udp relay")
        pool.execute {
            runCatching { while (input.read() >= 0) {} }
            runCatching { ds.close() }
        }
        pool.execute {
            val buf = ByteArray(65_536)
            while (true) {
                val pkt = DatagramPacket(buf, buf.size)
                ds.receive(pkt)
                val data = pkt.data
                if (data.size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte()) continue
                val target = readAddressFrom(data, 3) ?: continue
                val payload = data.copyOfRange(target.second, data.size)
                val r = buildReply(pkt.address, pkt.port) + payload
                runCatching { ds.send(DatagramPacket(r, r.size, pkt.address, pkt.port)) }
                DeviceTracker.record(device, payload.size, payload.size)
            }
        }
    }

    private fun readAddressFrom(data: ByteArray, start: Int): Pair<String, Int>? {
        val atyp = data[start].toInt() and 0xff
        var idx = start + 1
        val host = when (atyp) {
            0x01 -> {
                val b = data.copyOfRange(idx, idx + 4)
                idx += 4
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            0x03 -> {
                val len = data[idx].toInt() and 0xff
                idx += 1
                String(data, idx, len, Charsets.UTF_8).also { idx += len }
            }
            0x04 -> {
                val b = data.copyOfRange(idx, idx + 16)
                idx += 16
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            else -> return null
        }
        val port = (data[idx].toInt() and 0xff) * 256 + (data[idx + 1].toInt() and 0xff)
        return host to port
    }

    private fun buildReply(from: InetAddress, fromPort: Int): ByteArray {
        val bytes = from.address
        val atyp = if (bytes.size == 16) 0x04 else 0x01
        val head = ByteArray(4 + bytes.size + 2)
        head[2] = atyp.toByte()
        bytes.copyInto(head, 4)
        head[4 + bytes.size] = ((fromPort shr 8) and 0xff).toByte()
        head[4 + bytes.size + 1] = (fromPort and 0xff).toByte()
        return head
    }

    private fun readAddress(input: InputStream): Pair<String, Int>? {
        val atyp = input.read()
        val host = when (atyp) {
            0x01 -> {
                val b = ByteArray(4).also { input.read(it) }
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            0x03 -> {
                val len = input.read()
                String(ByteArray(len).also { input.read(it) }, Charsets.UTF_8)
            }
            0x04 -> {
                val b = ByteArray(16).also { input.read(it) }
                InetAddress.getByAddress(b).hostAddress ?: return null
            }
            else -> return null
        }
        val port = (input.read() and 0xff) * 256 + (input.read() and 0xff)
        return host to port
    }

    private fun authenticate(input: InputStream, output: OutputStream, password: String): Boolean {
        if (input.read() != 0x05) return false
        val nmethods = input.read()
        var hasPasswordMethod = false
        for (i in 0 until nmethods) {
            if (input.read() == 0x02) hasPasswordMethod = true
        }
        if (password.isEmpty()) {
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            return true
        }
        if (!hasPasswordMethod) return false
        output.write(byteArrayOf(0x05, 0x02.toByte()))
        output.flush()
        if (input.read() != 0x01) return false
        val ulen = input.read()
        val _user = ByteArray(ulen).also { input.read(it) }
        val plen = input.read()
        val pass = ByteArray(plen).also { input.read(it) }
        val ok = String(pass, Charsets.UTF_8) == password
        output.write(byteArrayOf(0x05, if (ok) 0x00.toByte() else 0x01.toByte()))
        output.flush()
        return ok
    }

    private fun pump(input: InputStream, output: OutputStream, device: DeviceTracker.Device, isUpload: Boolean) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                if (isUpload) DeviceTracker.record(device, n, 0) else DeviceTracker.record(device, 0, n)
            }
        } catch (e: Exception) {
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun reply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }
}
