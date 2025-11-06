package com.qihoo.net.monitor.httpurl

import android.util.Log
import com.qihoo.net.monitor.MetricsHolder
import com.qihoo.net.monitor.NetworkMetrics
import com.qihoo.net.monitor.NetworkMonitor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class MonitoredHttpURLConnection(
    url: URL,
    private val delegate: HttpURLConnection = url.openConnection() as HttpURLConnection
) : HttpURLConnection(url) {

    companion object {
        private const val TAG = "MonitoredHttpURL"
    }

    private val requestId = MetricsHolder.generateRequestId()
    private var monitorInitTime: Long = 0 // 监控初始化时间（首次触发入口的时间）
    private var dnsStartTime: Long = 0 // DNS 开始时间（= monitorInitTime）
    private var connectFinishTime: Long = 0 // 连接完成时间（首次获取到响应头/流的时间）
    private var firstByteTime: Long = 0 // 首包时间（首次读取到响应数据的时间）
    private var requestSize: Long = 0
    private var responseSize: Long = 0
    private var isMonitorInited = false // 避免重复初始化
    private var isConnected = false // 标记是否已完成连接

    init {
        MetricsHolder.getConnectionMap()[this] = requestId
        Log.i(TAG, "init() -> requestId: $requestId, delegate: ${delegate.hashCode()}")
    }

    /**
     * 统一初始化监控（所有入口都会调用）
     * 监控起点 = 首次触发任何可能建立连接的方法的时间（DNS 解析开始时间）
     */
    private fun initMonitorIfNeed() {
        if (isMonitorInited) return
        monitorInitTime = System.currentTimeMillis()
        dnsStartTime = System.nanoTime()
        isMonitorInited = true
        Log.i(TAG, "initMonitorIfNeed() -> 监控初始化，requestId: $requestId, 时间: $monitorInitTime")
    }

    /**
     * 标记连接完成（无论显式/隐式，只要连接建立就调用）
     * 统计 DNS 耗时（监控初始化到连接完成）
     */
    private fun markConnectFinished() {
        if (isConnected) return
        connectFinishTime = System.nanoTime()
        isConnected = true
        val dnsTime = (connectFinishTime - dnsStartTime) / 1000000 // 转毫秒
        Log.i(TAG, "markConnectFinished() -> 连接完成，requestId: $requestId, DNS 耗时: $dnsTime ms")
        MetricsHolder.setDnsTime(requestId, dnsTime)
    }

    // --------------- 显式 connect() 方法（仅业务显式调用时执行）---------------
    override fun connect() {
        initMonitorIfNeed() // 显式调用时初始化监控
        Log.i(TAG, "connect() -> 显式触发连接，requestId: $requestId")
        try {
            val start = System.nanoTime()
            delegate.connect() // 调用系统显式连接
            markConnectFinished() // 标记连接完成（统计 DNS 耗时）
            val connectTime = (System.nanoTime() - start) / 1000000
            MetricsHolder.setConnectTime(requestId, connectTime)
            Log.i(TAG, "connect() -> 显式连接成功，耗时: $connectTime ms, requestId: $requestId")
        } catch (e: IOException) {
            Log.e(TAG, "connect() -> 显式连接失败，requestId: $requestId", e)
            reportError(e)
            throw e
        }
    }

    // --------------- 隐式连接入口1：获取响应流 ---------------
    override fun getInputStream(): InputStream {
        initMonitorIfNeed() // 隐式连接时初始化监控
        Log.i(TAG, "getInputStream() -> 隐式触发连接，requestId: $requestId")
        return try {
            // 调用系统流方法（内部会触发隐式连接）
            val inputStream = delegate.inputStream
            // 连接已完成，标记并统计 DNS 耗时（这里的时间 = 系统连接完成时间）
            markConnectFinished()
            // 记录首包时间（首次获取流即视为首包到达）
            if (firstByteTime.toInt() == 0) {
                firstByteTime = System.currentTimeMillis()
                val ttfb = firstByteTime - monitorInitTime
                MetricsHolder.setTtfb(requestId, ttfb)
                Log.i(TAG, "getInputStream() -> 首包到达，TTFB: $ttfb ms, requestId: $requestId")
            }
            // 包装流统计响应大小
            MonitoredInputStream(inputStream) { responseSize += it }
        } catch (e: IOException) {
            Log.e(TAG, "getInputStream() -> 流获取失败，requestId: $requestId", e)
            reportError(e)
            throw e
        }
    }

    // --------------- 隐式连接入口2：获取请求流（POST/PUT 等）---------------
    override fun getOutputStream(): OutputStream {
        initMonitorIfNeed() // 隐式连接时初始化监控
        Log.i(TAG, "getOutputStream() -> 隐式触发连接，requestId: $requestId")
        return try {
            // 调用系统流方法（内部会触发隐式连接）
            val outputStream = delegate.outputStream
            // 连接已完成，标记并统计 DNS 耗时
            markConnectFinished()
            // 包装流统计请求大小
            MonitoredOutputStream(outputStream) { requestSize += it }
        } catch (e: IOException) {
            Log.e(TAG, "getOutputStream() -> 流获取失败，requestId: $requestId", e)
            reportError(e)
            throw e
        }
    }

    // --------------- 隐式连接入口3：获取响应码 ---------------
    override fun getResponseCode(): Int {
        initMonitorIfNeed() // 隐式连接时初始化监控
        Log.i(TAG, "getResponseCode() -> 隐式触发连接，requestId: $requestId")
        return try {
            // 调用系统方法（内部会触发隐式连接）
            val code = delegate.responseCode
            // 连接已完成，标记并统计 DNS 耗时
            markConnectFinished()
            Log.i(TAG, "getResponseCode() -> 响应码: $code, requestId: $requestId")
            code
        } catch (e: IOException) {
            Log.e(TAG, "getResponseCode() -> 获取响应码失败，requestId: $requestId", e)
            reportError(e)
            throw e
        }
    }

    // --------------- 其他必须重写的方法（委托给 delegate）---------------
    override fun disconnect() {
        Log.i(TAG, "disconnect() -> 释放资源，requestId: $requestId")
        try {
            reportMetrics() // 上报监控数据
        } finally {
            delegate.disconnect()
            MetricsHolder.getConnectionMap().remove(this)
        }
    }

    override fun usingProxy() = delegate.usingProxy()
    override fun getResponseMessage() = delegate.responseMessage
    override fun getRequestMethod() = delegate.requestMethod
    override fun setRequestMethod(method: String) = delegate.setRequestMethod(method)
    override fun getRequestProperties() = delegate.requestProperties
    override fun setRequestProperty(key: String, value: String) = delegate.setRequestProperty(key, value)
    override fun getURL() = delegate.url
    override fun setConnectTimeout(timeout: Int) = delegate.setConnectTimeout(timeout)
    override fun getConnectTimeout() = delegate.connectTimeout
    override fun setReadTimeout(timeout: Int) = delegate.setReadTimeout(timeout)
    override fun getReadTimeout() = delegate.readTimeout
    override fun getHeaderField(name: String) = delegate.getHeaderField(name)
    override fun getContentLength() = delegate.contentLength
    override fun getContentType() = delegate.contentType

    // --------------- 数据上报逻辑（不变）---------------
    private fun reportMetrics() {
        if (!isMonitorInited) return

        val totalTime = System.currentTimeMillis() - monitorInitTime
        val connectTime = if (connectFinishTime > 0) (System.nanoTime() - connectFinishTime) / 1000000 else -1L
        val ttfb = if (firstByteTime > 0) firstByteTime - monitorInitTime else -1L

        with(MetricsHolder) {
            setConnectTime(requestId, connectTime)
            setTtfb(requestId, ttfb)
            setRequestSize(requestId, requestSize)
            setResponseSize(requestId, responseSize)
        }

        val (responseCode, errorMessage) = try {
            Pair(getResponseCode(), getResponseMessage())
        } catch (e: IOException) {
            Pair(-1, e.message)
        }
        val success = responseCode in 200 until 300

        with(MetricsHolder) {
            setResponseCode(requestId, responseCode)
            setSuccessState(requestId, success)
            if (!success) errorMessage?.let { setErrorMsg(requestId, it) }
        }

        val metrics = NetworkMetrics(
            requestId = requestId,
            url = url.toString(),
            method = requestMethod,
            startTime = monitorInitTime,
            totalTime = totalTime,
            dnsTime = MetricsHolder.getDnsTime(requestId),
            connectTime = connectTime,
            sslTime = -1,
            ttfb = ttfb,
            requestSize = requestSize,
            responseSize = responseSize,
            success = success,
            responseCode = responseCode,
            errorMessage = if (success) null else errorMessage,
            networkEnv = NetworkMonitor.getNetworkEnv(),
            deviceInfo = NetworkMonitor.getDeviceInfo(),
            retryCount = 0,
            redirectCount = 0
        )
        NetworkMonitor.report(metrics)
        Log.i(TAG, "reportMetrics() -> 上报完成，requestId: $requestId, 总耗时: $totalTime ms")
    }

    private fun reportError(e: IOException) {
        if (!isMonitorInited) return

        val totalTime = System.currentTimeMillis() - monitorInitTime
        MetricsHolder.apply {
            setErrorMsg(requestId, e.message ?: "Unknown error")
            setSuccessState(requestId, false)
        }

        val metrics = NetworkMetrics(
            requestId = requestId,
            url = url.toString(),
            method = requestMethod,
            startTime = monitorInitTime,
            totalTime = totalTime,
            dnsTime = if (dnsStartTime > 0) (System.nanoTime() - dnsStartTime) / 1000000 else -1L,
            connectTime = -1,
            sslTime = -1,
            ttfb = -1,
            requestSize = requestSize,
            responseSize = responseSize,
            success = false,
            responseCode = -1,
            errorMessage = e.message,
            networkEnv = NetworkMonitor.getNetworkEnv(),
            deviceInfo = NetworkMonitor.getDeviceInfo(),
            retryCount = 0,
            redirectCount = 0
        )
        NetworkMonitor.report(metrics)
    }
    /**
     * 包装输入流，统计响应数据大小
     */
    private class MonitoredInputStream(
        private val delegate: InputStream,
        private val onBytesRead: (Long) -> Unit
    ) : InputStream() {

        override fun read(): Int {
            val b = delegate.read()
            if (b != -1) onBytesRead(1)
            return b
        }

        override fun read(b: ByteArray): Int {
            val len = delegate.read(b)
            if (len != -1) onBytesRead(len.toLong())
            return len
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val readLen = delegate.read(b, off, len)
            if (readLen != -1) onBytesRead(readLen.toLong())
            return readLen
        }

        override fun close() = delegate.close()
        override fun available() = delegate.available()
        override fun skip(n: Long) = delegate.skip(n)
        override fun mark(readlimit: Int) = delegate.mark(readlimit)
        override fun reset() = delegate.reset()
        override fun markSupported() = delegate.markSupported()
    }

    /**
     * 包装输出流，统计请求数据大小
     */
    private class MonitoredOutputStream(
        private val delegate: OutputStream,
        private val onBytesWritten: (Long) -> Unit
    ) : OutputStream() {

        override fun write(b: Int) {
            delegate.write(b)
            onBytesWritten(1)
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            onBytesWritten(b.size.toLong())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            onBytesWritten(len.toLong())
        }

        override fun close() = delegate.close()
        override fun flush() = delegate.flush()
    }
}