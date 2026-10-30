package com.lanfile.tv

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import org.json.JSONObject

/**
 * UDP 局域网宣告器（与 Python 参考实现的 UDP 发现协议完全互通）。
 *
 * 协议格式（UDP 端口 41234）：
 *   报文 = ASCII "LFT1" + JSON
 *   电视端周期广播：
 *     {"magic":"LFT1","t":"announce","v":1,"token":"","name":"...","device_id":"...",
 *      "ip":"192.168.x.x","tcp_port":41235,"http_port":9527}
 *   手机端广播 DISCOVER，电视端单播回应同样的 announce 报文。
 *
 * 作用：让 Python 手机端（或其他按本协议扫描的实现）能自动发现本机，
 * 与 Android 端基于 NSD 的自动发现并存、互为补充。
 */
class UdpAnnouncer(
    private val name: String,
    private val deviceId: String,
    private val controlPort: Int,
    private val httpPort: Int,
    private val discoverPort: Int = DISCOVER_PORT,
    private val beaconIntervalMs: Long = 3000L,
) {
    companion object {
        private const val TAG = "UdpAnnouncer"
        const val DISCOVER_PORT = 41234
        private const val MAGIC = "LFT1"
    }

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.broadcast = true
                sock.soTimeout = 500
                sock.bind(InetSocketAddress(discoverPort))
                socket = sock
                TvServer.diagPublic("udp announcer bound to $discoverPort, ip=${localIp()}")
                var lastBeacon = 0L
                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeacon >= beaconIntervalMs) {
                        beacon(sock)
                        lastBeacon = now
                    }
                    try {
                        val buf = ByteArray(65535)
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        handle(sock, pkt)
                    } catch (_: SocketTimeoutException) {
                        // 正常超时，继续下一轮
                    } catch (e: Exception) {
                        if (!running) break
                        TvServer.diagPublic("udp recv loop: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                TvServer.diagPublic("udp announcer FAILED: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "announcer failed", e)
            } finally {
                runCatching { socket?.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    /** 广播 announce 信标。 */
    private fun beacon(sock: DatagramSocket) {
        val msg = announcePayload()
        for (target in broadcastTargets()) {
            try {
                sock.send(DatagramPacket(msg, msg.size, target, discoverPort))
                TvServer.diagPublic("udp beacon -> $target:$discoverPort ok")
            } catch (e: Exception) {
                TvServer.diagPublic("udp beacon -> $target failed: ${e.message}")
            }
        }
    }

    /** 收到 DISCOVER 时单播回应。 */
    private fun handle(sock: DatagramSocket, pkt: DatagramPacket) {
        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
        // 兼容两种载荷格式：
        //   1) "LFT1" + JSON      —— 本实现与 Python 电视端使用的前缀形式
        //   2) 纯 JSON（含 "magic":"LFT1" 字段）—— Python 手机端实际发送的形式
        val jsonText = if (text.startsWith(MAGIC)) text.substring(MAGIC.length) else text
        val obj = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return
        }
        if (obj.optString("t") != "discover") return
        val msg = announcePayload()
        try {
            sock.send(DatagramPacket(msg, msg.size, pkt.address, pkt.port))
            TvServer.diagPublic("udp answered DISCOVER from ${pkt.address}")
        } catch (e: Exception) {
            TvServer.diagPublic("udp answer failed: ${e.message}")
        }
    }

    private fun announcePayload(): ByteArray {
        val ip = localIp()
        val obj = JSONObject()
        obj.put("magic", MAGIC)
        obj.put("t", "announce")
        obj.put("v", 1)
        obj.put("token", "")
        obj.put("name", name)
        obj.put("model", runCatching { android.os.Build.MODEL }.getOrDefault("Android"))
        obj.put("device_id", deviceId)
        obj.put("ip", ip)
        obj.put("tcp_port", controlPort)
        obj.put("http_port", httpPort)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** 广播目标：受限广播 + 各网卡的定向广播地址。 */
    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.interfaceAddresses) {
                    val b = addr.broadcast ?: continue
                    if (b is java.net.Inet4Address && b.isLoopbackAddress) continue
                    out.add(b)
                }
            }
        }
        return out
    }

    /** 取本机 IPv4 地址（优先非回环）。 */
    private fun localIp(): String {
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.interfaceAddresses) {
                    if (addr.address is java.net.Inet4Address && !addr.address.isLoopbackAddress) {
                        return addr.address.hostAddress ?: continue
                    }
                }
            }
        }
        return "127.0.0.1"
    }
}
