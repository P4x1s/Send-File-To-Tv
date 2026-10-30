package com.lanfile.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lanfile.shared.CONTROL_PORT
import com.lanfile.shared.DATA_PORT
import com.lanfile.shared.HttpProto
import com.lanfile.shared.NSD_SERVICE_TYPE

/**
 * 电视端 NSD 服务注册：在同网内广播 "_lanfile._tcp." 服务，
 * 手机端无需手动输入地址即可发现本设备。
 *
 * TXT 记录同时携带三个端口：
 * - 服务端口 = TCP 控制端口；
 * - `dp` = TCP 数据端口；
 * - `hp` = HTTP 上传端口（供「扫码即传」通道使用）。
 */
class NsdRegistrar(
    context: Context,
    private val controlPort: Int = CONTROL_PORT,
    private val dataPort: Int = DATA_PORT,
    private val httpPort: Int = HttpProto.HTTP_PORT,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val info = NsdServiceInfo().apply {
        // 服务名即手机端「打开 TV 时显示的名字」，与应用名统一为 SEND FILE TO TV。
        // 型号放在 TXT 的 "model" 属性里，避免污染显示名（多台电视靠 IP 区分）。
        serviceName = DEVICE_DISPLAY_NAME
        serviceType = NSD_SERVICE_TYPE
        setPort(controlPort)
        setAttribute("dp", dataPort.toString()) // 数据端口写在 TXT 记录中
        setAttribute("hp", httpPort.toString()) // HTTP 上传端口
        setAttribute("model", modelName())
    }
    private val regListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    fun register() = nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    fun unregister() = runCatching { nsd.unregisterService(regListener) }

    companion object {
        /** 设备显示名：与应用标题一致，统一为 SEND FILE TO TV。 */
        const val DEVICE_DISPLAY_NAME = "SEND FILE TO TV"

        /** 设备型号（仅作信息附带，不进入显示名）。 */
        fun modelName(): String = runCatching { android.os.Build.MODEL }.getOrDefault("Android")
    }
}
