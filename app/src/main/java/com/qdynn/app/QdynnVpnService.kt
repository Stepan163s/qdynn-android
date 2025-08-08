package com.qdynn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qdynn.app.dnstt.DnsttBridge
import com.qdynn.app.dnstt.DnsttBridgeFactory
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QdynnVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.qdynn.app.action.START"
        const val ACTION_STOP = "com.qdynn.app.action.STOP"

        const val EXTRA_DOMAIN = "extra_domain"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_DNS = "extra_dns"

        private const val TAG = "QdynnVpnService"
        private const val CHANNEL_ID = "qdynn_vpn"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var activeInstance: QdynnVpnService? = null

        fun deliverPacketFromGo(data: ByteArray) {
            activeInstance?.enqueueIncomingPacket(data)
        }
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunInputStream: FileInputStream? = null
    private var tunOutputStream: FileOutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private val incomingPacketQueue = LinkedBlockingQueue<ByteArray>(1024)

    private var dnstt: DnsttBridge? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val dns = intent.getStringExtra(EXTRA_DNS) ?: ""
                Log.i(TAG, "Starting VPN with domain=$domain dns=$dns")
                startTunnel(domain, password, dns)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping VPN")
                stopTunnel()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopTunnel()
        stopForeground(true)
        super.onRevoke()
    }

    override fun onDestroy() {
        activeInstance = null
        stopTunnel()
        stopForeground(true)
        super.onDestroy()
    }

    private fun startTunnel(domain: String, password: String, dns: String) {
        try {
            stopTunnel()

            val builder = Builder()
                .setSession("qdynn")
                .setMtu(1500)

            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)

            if (dns.isNotBlank()) {
                builder.addDnsServer(dns)
            }

            tunInterface = builder.establish()
            if (tunInterface == null) {
                Log.e(TAG, "Failed to establish TUN interface")
                return
            }

            val fd = tunInterface!!.fileDescriptor
            tunInputStream = FileInputStream(fd)
            tunOutputStream = FileOutputStream(fd)

            // Start Go bridge
            dnstt = DnsttBridgeFactory.create().also { bridge ->
                bridge.start(
                    domain = domain,
                    password = password,
                    dns = dns,
                    onPacketFromGo = { data -> deliverPacketFromGo(data) },
                    onLog = { msg -> Log.d(TAG, msg) }
                )
            }

            isRunning.set(true)
            startReaderThread()
            startWriterThread()

            Log.i(TAG, "TUN established and IO threads started")

        } catch (t: Throwable) {
            Log.e(TAG, "Error starting tunnel", t)
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        isRunning.set(false)

        readerThread?.interrupt()
        writerThread?.interrupt()

        try {
            dnstt?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping dnstt", t)
        } finally {
            dnstt = null
        }

        try {
            tunInputStream?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing TUN input", t)
        } finally {
            tunInputStream = null
        }

        try {
            tunOutputStream?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing TUN output", t)
        } finally {
            tunOutputStream = null
        }

        try {
            tunInterface?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing TUN fd", t)
        } finally {
            tunInterface = null
        }

        readerThread = null
        writerThread = null
        incomingPacketQueue.clear()
    }

    private fun startReaderThread() {
        val thread = Thread({
            val input = tunInputStream ?: return@Thread
            val buffer = ByteArray(32767)
            while (isRunning.get()) {
                try {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        if (!isRunning.get()) break
                        continue
                    }
                    handleOutboundPacket(buffer, read)
                } catch (ex: IOException) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Reader IO error", ex)
                    }
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "Reader unexpected error", t)
                    break
                }
            }
        }, "qdynn-tun-reader")
        readerThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun startWriterThread() {
        val thread = Thread({
            val output = tunOutputStream ?: return@Thread
            while (isRunning.get() || !incomingPacketQueue.isEmpty()) {
                try {
                    val packet = incomingPacketQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (packet.isEmpty()) continue
                    output.write(packet)
                } catch (ex: IOException) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Writer IO error", ex)
                    }
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "Writer unexpected error", t)
                    break
                }
            }
        }, "qdynn-tun-writer")
        writerThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun handleOutboundPacket(data: ByteArray, length: Int) {
        val copy = data.copyOf(length)
        dnstt?.sendPacket(copy) ?: sendToGo(copy)
    }

    private fun enqueueIncomingPacket(data: ByteArray) {
        if (!isRunning.get()) return
        incomingPacketQueue.offer(data)
    }

    private fun sendToGo(data: ByteArray) {
        Log.v(TAG, "Outbound packet size=${data.size}")
    }

    private fun ensureForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QDynn VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QDynn VPN")
            .setContentText("Подключено")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
} 