package com.qihoo.net.monitor.hook

import android.util.Log
import java.io.OutputStream
import java.nio.charset.Charset

// 监控输出流（请求数据）
class MonitoredOutputStream(
    private val delegate: OutputStream,
    private val socketInfo: SocketInfo
) : OutputStream() {
    private val TAG = "MonitoredOutputStream"
    private val REUSE_INTERVAL_NS = 500_000_000 // 500ms（避免分段写入误判）
    private var lastWriteTime = 0L
    private var isRequestHeaderParsed = false // 是否解析过请求头

    init {
        Log.d("MonitoredOutputStream", "已创建，关联 socketId: ${socketInfo.socketId}")
    }

    override fun write(b: Int) {
        delegate.write(b)
        handleWriteData(1, byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray) {
        delegate.write(b)
        handleWriteData(b.size.toLong(), b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        handleWriteData(len.toLong(), b, off, len)
    }

    // 处理写入的数据（新增字节数组参数用于解析请求头）
    private fun handleWriteData(bytes: Long, b: ByteArray, off: Int, len: Int) {
        val now = System.nanoTime()
        val interval = now - lastWriteTime
        lastWriteTime = now

        // 判断是否为新请求（无当前请求 或 间隔超过阈值）
        if (socketInfo.currentRequest == null || interval > REUSE_INTERVAL_NS) {
            // 初始化新请求
            socketInfo.reuseCount++
            val requestId = "${socketInfo.socketId}_${socketInfo.reuseCount}"
            socketInfo.currentRequest = RequestMetrics(
                requestId = requestId,
                parentSocketId = socketInfo.socketId
            ).apply {
                writeStart = now
                MetricsHolder.addActiveRequest(this)
            }
            Log.d(TAG, "新请求开始: $requestId, 复用次数: ${socketInfo.reuseCount}")
            isRequestHeaderParsed = false // 新请求重置头解析标记
        }

        // 解析请求头，获取 HTTP 方法（如 GET/POST）
        if (!isRequestHeaderParsed && socketInfo.currentRequest != null) {
            try {
                val requestHeader = String(b, off, len, Charset.forName("UTF-8"))
                // 匹配 HTTP 方法（GET/POST/PUT/DELETE 等）
                val methodMatch = Regex("^(GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s").find(requestHeader)
                if (methodMatch != null) {
                    val method = methodMatch.groupValues[1]
                    socketInfo.currentRequest?.method = method
                    isRequestHeaderParsed = true // 只解析一次
                    Log.d(TAG, "请求方法: $method, requestId: ${socketInfo.currentRequest?.requestId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析请求头失败", e)
            }
        }

        // 累加请求大小（修复原代码未赋值问题）
        socketInfo.currentRequest?.let {
            it.requestSize += bytes
        }

        // 更新最后活动时间
        socketInfo.lastActivityTime = now
    }

    // 其他方法委托给原始流
    override fun close() = delegate.close()
    override fun flush() = delegate.flush()
}