package com.qihoo.net.monitor.hook

// Socket 整体信息（生命周期内复用）
data class SocketInfo(
    val socketId: String, // Socket 唯一标识
    var host: String? = null,
    var port: Int = -1,
    var isSSL: Boolean = false,
    var url: String? = null,
    var connectStartTime: Long = 0, // Socket 连接开始时间
    var connectEndTime: Long = 0,   // Socket 连接结束时间
    var reuseCount: Int = 0, // 被复用次数
    var currentRequest: RequestMetrics? = null, // 当前正在处理的请求
    var lastActivityTime: Long = 0, // 最后一次读写时间（用于超时判断）
    var dnsStartTime: Long = 0,
    var dnsEndTime: Long = 0,
    var secureConnectStartTime: Long = 0,
    var secureConnectEndTime: Long = 0,
    var errorMessage: String? = null
)