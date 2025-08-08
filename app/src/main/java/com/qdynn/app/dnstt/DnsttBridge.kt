package com.qdynn.app.dnstt

import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy

interface DnsttBridge {
    fun start(
        domain: String,
        password: String,
        dns: String,
        onPacketFromGo: (ByteArray) -> Unit,
        onLog: (String) -> Unit
    )

    fun sendPacket(packet: ByteArray)

    fun stop()
}

object DnsttBridgeFactory {
    fun create(): DnsttBridge {
        return try {
            // Try to load gomobile-generated class reflectively
            Class.forName("com.qdynn.dnstt.mobile.Bridge")
            GoDnsttBridgeReflective()
        } catch (_: Throwable) {
            NoopDnsttBridge()
        }
    }
}

private class NoopDnsttBridge : DnsttBridge {
    private var running = false
    private var logger: ((String) -> Unit)? = null

    override fun start(
        domain: String,
        password: String,
        dns: String,
        onPacketFromGo: (ByteArray) -> Unit,
        onLog: (String) -> Unit
    ) {
        running = true
        logger = onLog
        onLog("dnstt Noop start: domain=$domain dns=$dns")
    }

    override fun sendPacket(packet: ByteArray) {
        if (!running) return
        logger?.invoke("dnstt Noop sendPacket size=${packet.size}")
    }

    override fun stop() {
        if (!running) return
        running = false
        logger?.invoke("dnstt Noop stop")
    }
}

private class GoDnsttBridgeReflective : DnsttBridge {
    private var bridgeInstance: Any? = null
    private var methodStart: Method? = null
    private var methodSend: Method? = null
    private var methodStop: Method? = null

    override fun start(
        domain: String,
        password: String,
        dns: String,
        onPacketFromGo: (ByteArray) -> Unit,
        onLog: (String) -> Unit
    ) {
        val bridgeClass = Class.forName("com.qdynn.dnstt.mobile.Bridge")
        bridgeInstance = bridgeClass.getDeclaredConstructor().newInstance()

        val packetHandlerIface = Class.forName("com.qdynn.dnstt.mobile.PacketHandler")
        val loggerIface = Class.forName("com.qdynn.dnstt.mobile.Logger")

        val packetHandlerProxy = Proxy.newProxyInstance(
            packetHandlerIface.classLoader,
            arrayOf(packetHandlerIface)
        ) { _, method, args ->
            if (method.name == "OnPacket" && args != null && args.isNotEmpty()) {
                val bytes = args[0] as ByteArray
                onPacketFromGo(bytes)
            }
            null
        }

        val loggerProxy = Proxy.newProxyInstance(
            loggerIface.classLoader,
            arrayOf(loggerIface)
        ) { _, method, args ->
            if (method.name == "OnLog" && args != null && args.isNotEmpty()) {
                val msg = args[0] as String
                onLog(msg)
            }
            null
        }

        methodStart = bridgeClass.getMethod(
            "Start",
            String::class.java,
            String::class.java,
            String::class.java,
            packetHandlerIface,
            loggerIface
        )
        methodSend = bridgeClass.getMethod("SendPacket", ByteArray::class.java)
        methodStop = bridgeClass.getMethod("Stop")

        methodStart?.invoke(bridgeInstance, domain, password, dns, packetHandlerProxy, loggerProxy)
    }

    override fun sendPacket(packet: ByteArray) {
        try {
            methodSend?.invoke(bridgeInstance, packet)
        } catch (t: Throwable) {
            Log.w("GoDnsttBridge", "sendPacket failed", t)
        }
    }

    override fun stop() {
        try {
            methodStop?.invoke(bridgeInstance)
        } catch (_: Throwable) {
        } finally {
            bridgeInstance = null
        }
    }
} 