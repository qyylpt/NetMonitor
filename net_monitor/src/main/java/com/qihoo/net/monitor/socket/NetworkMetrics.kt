package com.qihoo.net.monitor.socket

data class NetworkMetrics(
    val requestId: String,
    val url: String,
    val method: String,
    val startTime: Long,
    val totalTime: Long,
    val dnsTime: Long = -1,
    val connectTime: Long = -1,
    val sslTime: Long = -1,
    val ttfb: Long = -1,
    val requestSize: Long = 0,
    val responseSize: Long = 0,
    val success: Boolean = false,
    val responseCode: Int = -1,
    val errorMessage: String? = null,
    val networkEnv: String = "",
    val deviceInfo: String = "",
    val retryCount: Int = 0,
    val redirectCount: Int = 0
)

interface NetworkMetricsListener {
    fun onMetricsCollected(metrics: NetworkMetrics)
}