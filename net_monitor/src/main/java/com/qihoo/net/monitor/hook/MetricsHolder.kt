package com.qihoo.net.monitor.hook

import java.net.Socket
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object MetricsHolder {
    // Socket 实例 -> SocketInfo（弱引用，避免内存泄漏）
    val socketInfoMap = WeakHashMap<Socket, SocketInfo>()
    // 活跃请求（用于超时扫描）
    private val activeRequests = ConcurrentHashMap<String, RequestMetrics>()

    // 生成唯一 ID
    fun generateId(): String = UUID.randomUUID().toString().substring(0, 8)

    // 获取或创建 SocketInfo
    fun getOrCreateSocketInfo(socket: Socket): SocketInfo {
        return socketInfoMap.getOrPut(socket) {
            SocketInfo(socketId = generateId()).apply {
                lastActivityTime = System.nanoTime()
            }
        }
    }

    // 获取 SocketInfo
    fun getSocketInfo(socket: Socket): SocketInfo? = socketInfoMap[socket]

    // 移除 SocketInfo
    fun removeSocketInfo(socket: Socket) {
        socketInfoMap.remove(socket)?.currentRequest?.let {
            activeRequests.remove(it.requestId)
        }
    }

    // 添加活跃请求
    fun addActiveRequest(request: RequestMetrics) {
        activeRequests[request.requestId] = request
    }

    // 移除活跃请求
    fun removeActiveRequest(requestId: String) {
        activeRequests.remove(requestId)
    }

    // 获取所有活跃请求（用于超时扫描）
    fun getActiveRequests(): Collection<RequestMetrics> = activeRequests.values.toList()
}