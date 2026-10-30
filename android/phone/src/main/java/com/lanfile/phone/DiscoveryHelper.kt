package com.lanfile.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lanfile.shared.HttpProto
import com.lanfile.shared.NSD_SERVICE_TYPE

/**
 * 电视端设备信息。
 *
 * @property name 设备名（NSD 服务名）
 * @property host 局域网 IP
 * @property port TCP 控制端口
 * @property dataPort TCP 数据端口
 * @property httpPort HTTP 上传端口（电视端内置 HTTP 服务器，「扫码即传」通道使用）
 */
data class TvDevice(
    val name: String,
    val host: String,
    val port: Int,      // 控制端口
    val dataPort: Int,  // 数据端口
    val httpPort: Int,  // HTTP 上传端口
)

/**
 * 基于 NsdManager 的局域网自动发现（零配置）：
 * 持续发现 "_lanfile._tcp." 服务并解析出 IP/端口，无需用户手动输入地址。
 *
 * TXT 记录解析：
 * - `dp` = TCP 数据端口，缺省取 port+1；
 * - `hp` = HTTP 上传端口，缺省取 [HttpProto.HTTP_PORT]。
 */
class DiscoveryHelper(
    private val context: Context,
    private val onDevice: (TvDevice) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val seen = mutableSetOf<String>()

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val port = info.port
            // 数据端口写在 TXT 记录 "dp" 中（字节数组），缺省取 port+1
            val dpBytes = info.attributes["dp"] as? ByteArray
            val dataPort = dpBytes?.let { String(it).toIntOrNull() } ?: (port + 1)
            // HTTP 上传端口写在 TXT 记录 "hp" 中，缺省用协议默认端口
            val hpBytes = info.attributes["hp"] as? ByteArray
            val httpPort = hpBytes?.let { String(it).toIntOrNull() } ?: HttpProto.HTTP_PORT
            val dev = TvDevice(info.serviceName, host, port, dataPort, httpPort)
            val key = "${info.serviceName}@$host"
            if (seen.add(key)) onDevice(dev)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType == NSD_SERVICE_TYPE) {
                nsd.resolveService(service, resolveListener)
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() = nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    fun stop() = runCatching { nsd.stopServiceDiscovery(discoveryListener) }
}
