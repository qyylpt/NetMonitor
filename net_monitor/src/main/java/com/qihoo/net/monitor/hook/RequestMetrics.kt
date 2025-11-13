package com.qihoo.net.monitor.hook

// 单个请求的指标（每次复用生成新实例）
data class RequestMetrics(
    val requestId: String, // 单个请求唯一标识
    val parentSocketId: String, // 关联的 SocketId
    var method: String? = null, // 协议方法（如 GET/POST，需额外解析）
    var writeStart: Long = 0, // 请求写入开始时间
    var readStart: Long = 0, // 响应读取开始时间
    var requestSize: Long = 0, // 请求数据大小
    var responseSize: Long = 0, // 响应数据大小
    var ttfb: Long = 0, // 首字节时间
    var responseCode: Int = -1, // 响应码（需额外解析）
    var isCompleted: Boolean = false // 是否已完成上报
)