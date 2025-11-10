package com.qihoo.net.monitor.httpurl

import android.util.Log
import com.qihoo.net.monitor.MetricsHolder
import com.qihoo.net.monitor.NetworkMetrics
import com.qihoo.net.monitor.NetworkMonitor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MonitoredHttpURLConnection(
    url: URL,
    private val delegate: HttpURLConnection = url.openConnection() as HttpURLConnection
) : HttpURLConnection(url) {

    companion object {
        private const val TAG = "MonitoredHttpURL"
    }

    private val requestId = MetricsHolder.generateRequestId()

    // 整体调用时间
    private var callStart: Long = System.nanoTime()  // 整个调用开始时间
    private var monitorInitTime: Long = 0 // 监控初始化时间

    // 连接相关时间点
    private var connectStart: Long = 0     // 连接开始时间
    private var secureConnectStart: Long = 0  // 安全连接开始时间
    private var secureConnectEnd: Long = 0    // 安全连接结束时间
    private var connectEnd: Long = 0       // 连接结束时间
    private var responseHeadersEnd: Long = 0   // 响应头接收完成

    // DNS相关
    private var dnsStartTime: Long = 0
    private var dnsEndTime: Long = 0
    private var isDnsResolved = false
    private var dnsResolvedAddresses: Array<InetAddress>? = null
    private var dnsTime: Long = -1
    private var isDnsFromCache = false

    // SSL相关
    private var sslTime: Long = 0
    private val isHttps = url.protocol == "https"

    // 其他指标
    private var ttfbTime: Long = 0
    private var requestSize: Long = 0
    private var responseSize: Long = 0
    private var localResponseCode = -1

    // 状态标记
    private var isMonitorInited = false
    private var isConnected = false
    private var hasReceivedHeaders = false

    init {
        MetricsHolder.getConnectionMap()[this] = requestId
        Log.i(TAG, "init() -> requestId: $requestId, delegate: ${delegate.hashCode()}")
        resolveDns()
        setupSslMonitoringIfNeeded()
    }

    private fun resolveDns() {
        val host = url.host
        Thread {
            try {
                dnsStartTime = System.nanoTime()

                // 第一次DNS查询
                val firstLookupStart = System.nanoTime()
                val firstResult = InetAddress.getAllByName(host)
                val firstLookupTime = System.nanoTime() - firstLookupStart

                // 第二次DNS查询（检查缓存）
                val secondLookupStart = System.nanoTime()
                val secondResult = InetAddress.getAllByName(host)
                val secondLookupTime = System.nanoTime() - secondLookupStart

                isDnsFromCache = secondLookupTime < firstLookupTime / 2
                dnsEndTime = System.nanoTime()
                dnsTime = (dnsEndTime - dnsStartTime) / 1_000_000
                isDnsResolved = true
                dnsResolvedAddresses = firstResult

                val ips = firstResult.joinToString(", ") { it.hostAddress }
                Log.d(TAG, """
                    DNS解析完成:
                    主机: $host
                    IP地址: $ips
                    解析耗时: $dnsTime ms
                    是否使用缓存: $isDnsFromCache
                    第一次查询: ${firstLookupTime / 1_000_000} ms
                    第二次查询: ${secondLookupTime / 1_000_000} ms
                    requestId: $requestId
                """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "DNS解析失败: $host, requestId: $requestId", e)
                dnsTime = -1
            }
        }.apply {
            name = "DNS-Resolver-$requestId"
            isDaemon = true
            start()
        }
    }

    private fun setupSslMonitoringIfNeeded() {
        try {
            if (!isHttps || delegate !is HttpsURLConnection) return

            val originalFactory = delegate.sslSocketFactory
            delegate.sslSocketFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites() = originalFactory.defaultCipherSuites
                override fun getSupportedCipherSuites() = originalFactory.supportedCipherSuites

                override fun createSocket(s: Socket?, host: String, port: Int, autoClose: Boolean): Socket {
                    return wrapSocket(originalFactory.createSocket(s, host, port, autoClose), host)
                }

                override fun createSocket(host: String, port: Int): Socket {
                    return wrapSocket(originalFactory.createSocket(host, port), host)
                }

                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    return wrapSocket(originalFactory.createSocket(host, port, localHost, localPort), host)
                }

                override fun createSocket(host: InetAddress, port: Int): Socket {
                    return wrapSocket(originalFactory.createSocket(host, port), host.hostName)
                }

                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    return wrapSocket(originalFactory.createSocket(address, port, localAddress, localPort), address.hostName)
                }

                private fun wrapSocket(socket: Socket, host: String): Socket {
                    // TCP连接已完成，记录安全连接开始时间
                    secureConnectStart = System.nanoTime()

                    // 计算纯TCP连接时间（不含SSL）
                    if (connectStart > 0) {
                        val tcpConnectTime = (secureConnectStart - connectStart) / 1_000_000
                        Log.d(TAG, "TCP连接完成，耗时: $tcpConnectTime ms, requestId: $requestId")
                    }

                    if (socket is SSLSocket) {
                        socket.addHandshakeCompletedListener { event ->
                            secureConnectEnd = System.nanoTime()
                            sslTime = (secureConnectEnd - secureConnectStart) / 1_000_000
                            // 连接完成时间就是SSL握手完成时间
                            connectEnd = secureConnectEnd
                            isConnected = true

                            val session = event.session
                            Log.d(TAG, """
                                SSL握手完成:
                                耗时: $sslTime ms
                                协议: ${session.protocol}
                                加密套件: ${session.cipherSuite}
                                总连接耗时: ${calculateConnectTime()} ms
                                requestId: $requestId
                            """.trimIndent())
                        }
                    }
                    return socket
                }
            }
            Log.d(TAG, "SSL监控已安装, requestId: $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "SSL监控安装失败, requestId: $requestId", e)
        }
    }

    /**
     * 计算连接耗时
     */
    private fun calculateConnectTime(): Long {
        return if (isHttps) {
            if (secureConnectEnd > 0) {
                // 包括TCP连接和SSL握手的总时间
                (secureConnectEnd - connectStart) / 1_000_000
            } else if (secureConnectStart > 0) {
                // SSL握手尚未完成，只返回TCP连接时间
                (secureConnectStart - connectStart) / 1_000_000
            } else {
                -1 // 连接尚未开始或未完成
            }
        } else {
            // HTTP连接
            if (connectEnd > 0) {
                (connectEnd - connectStart) / 1_000_000
            } else {
                -1 // 连接尚未完成
            }
        }
    }

    /**
     * 记录响应头接收完成
     */
    private fun markResponseHeadersReceived() {
        if (hasReceivedHeaders) return

        responseHeadersEnd = System.nanoTime()
        hasReceivedHeaders = true

        // 计算TTFB (Time to First Byte) = 响应头结束时间 - 连接开始时间
        if (connectStart > 0) {
            ttfbTime = (responseHeadersEnd - connectStart) / 1_000_000
            Log.d(TAG, "响应头接收完成，TTFB: $ttfbTime ms, requestId: $requestId")
        }
    }

    /**
     * 统一初始化监控（所有入口都会调用）
     */
    private fun initMonitorIfNeed() {
        if (isMonitorInited) return
        monitorInitTime = System.currentTimeMillis()
        connectStart = System.nanoTime()  // 记录连接开始时间
        isMonitorInited = true
        Log.i(TAG, "initMonitorIfNeed() -> 监控初始化，requestId: $requestId, 时间: $monitorInitTime")
    }

    /**
     * 标记连接完成
     */
    private fun markConnectFinished() {
        if (isConnected) return

        // 对于HTTP请求，直接记录连接结束时间
        // 对于HTTPS请求，连接结束时间在SSL握手完成时设置
        if (!isHttps) {
            connectEnd = System.nanoTime()
            isConnected = true
        }

        // 如果连接已完成，计算连接时间
        if (isConnected) {
            val connectTime = calculateConnectTime()

            // 使用预解析的DNS时间，如果可用的话
            val finalDnsTime = if (isDnsResolved) {
                dnsTime
            } else {
                // 如果预解析未完成，使用估算时间
                (connectStart - dnsStartTime) / 1_000_000
            }

            Log.i(TAG, """
                连接完成:
                requestId: $requestId
                类型: ${if (isHttps) "HTTPS" else "HTTP"}
                连接耗时: $connectTime ms
                DNS耗时: $finalDnsTime ms
                DNS来源: ${if (isDnsResolved) "预解析" else "估算"}
                DNS缓存: $isDnsFromCache
            """.trimIndent())
        }
    }

    // --------------- 显式 connect() 方法（仅业务显式调用时执行）---------------
    override fun connect() {
        initMonitorIfNeed() // 显式调用时初始化监控
        Log.i(TAG, "connect() -> 显式触发连接，requestId: $requestId")
        try {
            delegate.connect() // 调用系统显式连接
            markConnectFinished()
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
            // 连接已完成，标记并统计连接耗时
            markConnectFinished()

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
            // 连接已完成，标记并统计连接耗时
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
            localResponseCode = delegate.responseCode
            // 连接已完成，标记并统计连接耗时
            markConnectFinished()
            // 记录响应头接收完成时间
            markResponseHeadersReceived()
            Log.i(TAG, "getResponseCode() -> 响应码: $localResponseCode, requestId: $requestId")
            localResponseCode
        } catch (e: IOException) {
            Log.e(TAG, "getResponseCode() -> 获取响应码失败，requestId: $requestId", e)
            reportError(e)
            throw e
        }
    }

    // --------------- 覆盖更多可能获取响应头的方法 ---------------
    override fun getHeaderField(name: String): String {
        initMonitorIfNeed()
        val value = delegate.getHeaderField(name)
        markResponseHeadersReceived()
        return value
    }

    override fun getHeaderFields(): Map<String, List<String>> {
        initMonitorIfNeed()
        val headers = delegate.headerFields
        markResponseHeadersReceived()
        return headers
    }

    override fun getHeaderFieldKey(n: Int): String? {
        initMonitorIfNeed()
        val key = delegate.getHeaderFieldKey(n)
        markResponseHeadersReceived()
        return key
    }

    override fun getContentLength(): Int {
        initMonitorIfNeed()
        val length = delegate.contentLength
        markResponseHeadersReceived()
        return length
    }

    override fun getContentType(): String {
        initMonitorIfNeed()
        val type = delegate.contentType
        markResponseHeadersReceived()
        return type
    }

    override fun getResponseMessage(): String {
        initMonitorIfNeed()
        val message = delegate.responseMessage
        markResponseHeadersReceived()
        return message
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
    override fun setRequestMethod(method: String) = delegate.setRequestMethod(method)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = delegate.requestProperties
    override fun setRequestProperty(key: String, value: String) = delegate.setRequestProperty(key, value)
    override fun getURL(): URL = delegate.url
    override fun setConnectTimeout(timeout: Int) = delegate.setConnectTimeout(timeout)
    override fun getConnectTimeout() = delegate.connectTimeout
    override fun setReadTimeout(timeout: Int) = delegate.setReadTimeout(timeout)
    override fun getReadTimeout() = delegate.readTimeout

    // --------------- 数据上报逻辑 ---------------
    private fun reportMetrics() {
        if (!isMonitorInited) return

        val totalTime = System.currentTimeMillis() - monitorInitTime
        val success = localResponseCode in 200 until 300
        val errorMessage = try {
            delegate.responseMessage
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }

        // 确保连接时间已计算
        val connectTime = calculateConnectTime()

        // 使用响应头接收完成时间作为TTFB
        val finalTtfb = if (responseHeadersEnd > 0) {
            (responseHeadersEnd - connectStart) / 1_000_000
        } else {
            ttfbTime
        }

        with(MetricsHolder) {
            setDnsTime(requestId, dnsTime)
            setSslTime(requestId, sslTime)
            setTtfb(requestId, finalTtfb)
            setSuccessState(requestId, success)
            setResponseCode(requestId, localResponseCode)
            setConnectTime(requestId, connectTime)
            setRequestSize(requestId, requestSize)
            setResponseSize(requestId, responseSize)
            if (!success) setErrorMsg(requestId, errorMessage)
        }

        val metrics = NetworkMetrics(
            requestId = requestId,
            url = url.toString(),
            method = requestMethod,
            startTime = monitorInitTime,
            totalTime = totalTime,
            dnsTime = dnsTime,
            connectTime = connectTime,
            sslTime = sslTime,
            ttfb = finalTtfb,
            requestSize = requestSize,
            responseSize = responseSize,
            success = success,
            responseCode = localResponseCode,
            errorMessage = if (success) null else errorMessage,
            networkEnv = NetworkMonitor.getNetworkEnv(),
            deviceInfo = NetworkMonitor.getDeviceInfo(),
            retryCount = 0,
            redirectCount = 0
        )

        NetworkMonitor.report(metrics)
        Log.i(TAG, """
            请求完成:
            URL: $url
            总耗时: $totalTime ms
            DNS耗时: $dnsTime ms
            连接耗时: $connectTime ms
            SSL耗时: $sslTime ms
            TTFB: $finalTtfb ms
            请求大小: $requestSize bytes
            响应大小: $responseSize bytes
            响应码: $localResponseCode
            requestId: $requestId
        """.trimIndent())
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
            dnsTime = if (dnsStartTime > 0) (System.nanoTime() - dnsStartTime) / 1_000_000 else -1L,
            connectTime = if (connectStart > 0) (System.nanoTime() - connectStart) / 1_000_000 else -1L,
            sslTime = sslTime,
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

        Log.e(TAG, """
            请求失败:
            URL: $url
            错误: ${e.message}
            耗时: $totalTime ms
            requestId: $requestId
        """.trimIndent())
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