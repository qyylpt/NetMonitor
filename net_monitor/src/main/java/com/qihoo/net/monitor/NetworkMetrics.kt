package com.qihoo.net.monitor

// 1. 设备信息数据类
data class DeviceInfo(
    val appVersion: String,
    val deviceModel: String,
    val osVersion: String,
    val deviceMemory: Long // MB
)

// 2. 网络环境信息
data class NetworkEnv(
    val networkType: String,
    val signalStrength: Int?,
    val carrier: String?
)

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
)