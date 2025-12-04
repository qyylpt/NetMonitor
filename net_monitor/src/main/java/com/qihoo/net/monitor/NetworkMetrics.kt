package com.qihoo.net.monitor

import org.json.JSONObject

// 1. 设备信息数据类
data class DeviceInfo(
    val appVersion: String,
    val deviceModel: String,
    val osVersion: String,
    val deviceMemory: Long // MB
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("app_version", appVersion)
            put("device_model", deviceModel)
            put("os_version", osVersion)
            put("device_memory", deviceMemory)
        }
    }
}

// 2. 网络环境信息
data class NetworkEnv(
    val networkType: String,
    val signalStrength: Int?,
    val carrier: String?
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("network_type", networkType)
            putOpt("signal_strength", signalStrength)
            putOpt("carrier", carrier)
        }
    }
}

// 3. 网络监控指标
data class NetworkMetrics(
    // 请求基础信息
    val requestId: String,
    val url: String,
    val method: String,
    val startTime: Long, // ms

    // 性能指标
    val totalTime: Long, // ms
    val dnsTime: Long, // ms
    val connectTime: Long, // ms
    val sslTime: Long, // ms
    val ttfb: Long, // ms
    val requestSize: Long, // bytes
    val responseSize: Long, // bytes

    // 结果状态
    val success: Boolean,
    val responseCode: Int,
    val errorMessage: String?,

    // 网络环境
    val networkEnv: NetworkEnv,

    // 设备与系统
    val deviceInfo: DeviceInfo?,

    // 扩展信息
    val retryCount: Int,
    val redirectCount: Int
) {
    private var reportJsonStr: String = ""

    init {
        convertJson()
    }

    private fun convertJson() {
        try {
            val reportJson = JSONObject().apply {
                // 请求基础信息（
                put("request_id", requestId)
                put("url", url)
                put("method", method)
                put("start_time", startTime)

                // 性能指标
                put("total_time", totalTime)
                put("dns_time", dnsTime)
                put("connect_time", connectTime)
                put("ssl_time", sslTime)
                put("ttfb", ttfb) // ttfb 是缩写，保持原样
                put("request_size", requestSize)
                put("response_size", responseSize)

                // 结果状态
                put("success", success)
                put("response_code", responseCode)
                putOpt("error_message", errorMessage)

                // 网络环境
                put("network_env", networkEnv.toJSONObject())

                // 设备信息
                putOpt("device_info", deviceInfo?.toJSONObject())

                // 扩展信息
                put("retry_count", retryCount)
                put("redirect_count", redirectCount)
            }

            reportJsonStr = reportJson.toString();
        } catch (e: Exception) {
            reportJsonStr = ""
        }
    }
}