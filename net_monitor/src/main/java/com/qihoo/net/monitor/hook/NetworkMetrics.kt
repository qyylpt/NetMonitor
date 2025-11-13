package com.qihoo.net.monitor.hook

// 最终上报的指标实体
data class NetworkMetrics(
    val requestId: String,
    val url: String,
    val method: String?,
    val startTime: Long,
    val totalTime: Long,
    val dnsTime: Long,
    val connectTime: Long,
    val sslTime: Long,
    val ttfb: Long,
    val requestSize: Long,
    val responseSize: Long,
    val success: Boolean,
    val responseCode: Int,
    val errorMessage: String?,
    val networkEnv: String,
    val deviceInfo: String
)