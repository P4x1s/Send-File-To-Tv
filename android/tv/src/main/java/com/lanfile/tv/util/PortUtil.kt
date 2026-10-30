package com.lanfile.tv.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网工具类（对齐 sunshine-send 的 PortUtil）。
 *
 * 用于取得本机局域网 IP，拼接出手机可访问的「http://ip:port」上传地址。
 */
object PortUtil {

    /**
     * 获取本机在局域网中的 IPv4 地址。
     *
     * 遍历所有已启用（up）的非回环网卡，返回第一个非回环 [Inet4Address]。
     * TV 场景下即 Wi-Fi / 以太网地址。
     *
     * @return 形如 `192.168.1.23` 的 IP；获取失败时返回 null，调用方回退 127.0.0.1
     */
    fun lan(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
