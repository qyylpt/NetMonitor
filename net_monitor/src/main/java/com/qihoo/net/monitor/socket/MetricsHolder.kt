package com.qihoo.net.monitor.socket

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MetricsHolder {
    // 请求ID到指标数据的映射
    private val metricsMap = ConcurrentHashMap<String, MutableMap<String, Any>>()

    // Socket对象到请求信息的映射
    private val socketInfoMap = ConcurrentHashMap<Any, SocketInfo>()

    // 生成唯一请求ID
    fun generateRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    fun getOrCreateSocketInfo(socket: Any): SocketInfo {
        return socketInfoMap.computeIfAbsent(socket) { SocketInfo(generateRequestId()) }
    }

    fun getSocketInfo(socket: Any): SocketInfo? = socketInfoMap[socket]

    fun removeSocketInfo(socket: Any) {
        socketInfoMap.remove(socket)
    }

    fun getOrCreateMetrics(requestId: String): MutableMap<String, Any> {
        return metricsMap.computeIfAbsent(requestId) { mutableMapOf() }
    }

    fun getMetrics(requestId: String): MutableMap<String, Any>? = metricsMap[requestId]

    fun clearMetrics(requestId: String) {
        metricsMap.remove(requestId)
    }
}

data class SocketInfo(
    var requestId: String,
    var host: String? = null,
    var port: Int = -1,
    var isSSL: Boolean = false,
    var url: String? = null,
    var method: String = "GET",
    var connectStartTime: Long = 0,
    var secureConnectStartTime: Long = 0,
    var secureConnectEndTime: Long = 0,
    var dnsStartTime: Long = 0,
    var dnsEndTime: Long = 0,
    var connectEndTime: Long = 0,
    var responseHeadersEndTime: Long = 0,
    var totalRequestSize: Long = 0,
    var totalResponseSize: Long = 0,
    var responseCode: Int = -1,
    var errorMessage: String? = ""
)