import android.util.Log
import com.qihoo.net.monitor.hook.MetricsHolder
import com.qihoo.net.monitor.hook.NetworkMetrics
import com.qihoo.net.monitor.hook.NetworkMonitor
import com.qihoo.net.monitor.hook.RequestMetrics
import com.qihoo.net.monitor.hook.SocketInfo
import java.io.InputStream
import java.nio.charset.Charset

// 监控输入流（响应数据）
class MonitoredInputStream(
    private val delegate: InputStream,
    private val socketInfo: SocketInfo
) : InputStream() {
    private val TAG = "MonitoredInputStream"
    private var isFirstRead = true // 用于计算 TTFB
    private var contentLength: Long = -1 // 响应体总长度（从响应头获取）
    private var bytesRead: Long = 0 // 已读取的响应体字节数
    private var isChunked: Boolean = false // 是否分块传输
    private var isHeaderParsed = false // 响应头是否解析完成
    private val headerBuffer = StringBuilder() // 缓存响应头数据
    private var isRequestCompleted = false // 避免重复上报

    init {
        Log.d("MonitoredInputStream", "已创建，关联 socketId: ${socketInfo.socketId}")
    }

    override fun read(): Int {
        val b = delegate.read()
        if (b == -1) {
            handleReadComplete()
        } else if (b != -1) {
            handleReadData(1)
            // 单字节读取时解析响应头
            parseHeader(byteArrayOf(b.toByte()), 0, 1)
        }
        return b
    }

    override fun read(b: ByteArray): Int {
        val len = delegate.read(b)
        if (len == -1) {
            handleReadComplete()
        } else if (len > 0) {
            parseHeader(b, 0, len)
            handleReadData(len.toLong())
        }
        return len
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val readLen = delegate.read(b, off, len)
        if (readLen == -1) {
            handleReadComplete()
        } else if (readLen > 0) {
            parseHeader(b, off, readLen)
            handleReadData(readLen.toLong())
        }
        return readLen
    }

    // 解析 HTTP 响应头，提取 Content-Length 或分块标记
    private fun parseHeader(b: ByteArray, off: Int, len: Int) {
        if (isHeaderParsed) return

        // 将字节转换为字符串（UTF-8 编码）
        val headerPart = String(b, off, len, Charset.forName("UTF-8"))
        headerBuffer.append(headerPart)

        // 响应头结束标记：\r\n\r\n（空行）
        val headerEndIndex = headerBuffer.indexOf("\r\n\r\n")
        if (headerEndIndex != -1) {
            isHeaderParsed = true
            val headerStr = headerBuffer.substring(0, headerEndIndex)

            // 提取响应码（如 HTTP/1.1 200 OK）
            val statusLine = headerStr.lines().firstOrNull() ?: ""
            val responseCode = Regex("\\s(\\d+)\\s").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()
            socketInfo.currentRequest?.responseCode = responseCode ?: -1

            // 提取 Content-Length
            contentLength = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                .find(headerStr)?.groupValues?.get(1)?.toLongOrNull() ?: -1

            // 提取 Transfer-Encoding: chunked
            isChunked = headerStr.contains("Transfer-Encoding: chunked", ignoreCase = true)

            Log.d(TAG, "解析响应头: 响应码=$responseCode, Content-Length=$contentLength, 分块=$isChunked")
        }
    }

    // 处理读取到的数据
    private fun handleReadData(bytes: Long) {
        socketInfo.currentRequest?.let { request ->
            // 首次读取，记录 TTFB（从连接完成到首字节的时间）
            if (isFirstRead && socketInfo.connectEndTime > 0) {
                request.ttfb = (System.nanoTime() - socketInfo.connectEndTime) / 1_000_000
                isFirstRead = false
                Log.d(TAG, "TTFB 计算完成: ${request.ttfb} ms, requestId: ${request.requestId}")
            }

            // 累加响应大小
            request.responseSize += bytes
            request.readStart = if (request.readStart == 0L) System.nanoTime() else request.readStart

            // 检查响应是否完成（基于协议）
            if (isHeaderParsed && !isRequestCompleted) {
                bytesRead += bytes
                checkResponseComplete()
            }
        }
        // 更新最后活动时间
        socketInfo.lastActivityTime = System.nanoTime()
    }

    // 检查响应是否读取完成（核心逻辑）
    private fun checkResponseComplete() {
        val isComplete = when {
            // 1. 已知 Content-Length：已读取字节数 >= 总长度
            contentLength != -1L -> bytesRead >= contentLength
            // 2. 分块传输：简单判断（实际需解析分块结束标记 0\r\n\r\n）
            isChunked -> bytesRead > 0 && headerBuffer.contains("0\r\n\r\n")
            // 3. 未知长度：默认读取 1KB 后视为完成（可根据业务调整）
            else -> bytesRead >= 1024
        }

        if (isComplete) {
            handleReadComplete()
        }
    }

    // 读取完成（响应结束）
    private fun handleReadComplete() {
        if (isRequestCompleted) return // 避免重复上报

        socketInfo.currentRequest?.takeIf { !it.isCompleted }?.let { request ->
            reportRequestMetrics(request, socketInfo)
            request.isCompleted = true
            isRequestCompleted = true
            socketInfo.currentRequest = null // 重置，准备下一次请求
            MetricsHolder.removeActiveRequest(request.requestId)
            Log.d(TAG, "请求结束，已上报: ${request.requestId}")
        }
    }

    // 上报单个请求的指标
    private fun reportRequestMetrics(request: RequestMetrics, socketInfo: SocketInfo) {
        val dnsTime = if (socketInfo.dnsStartTime > 0 && socketInfo.dnsEndTime > 0) {
            (socketInfo.dnsEndTime - socketInfo.dnsStartTime) / 1_000_000
        } else -1

        val connectTime = if (socketInfo.connectStartTime > 0 && socketInfo.connectEndTime > 0) {
            (socketInfo.connectEndTime - socketInfo.connectStartTime) / 1_000_000
        } else -1

        val sslTime = if (socketInfo.secureConnectStartTime > 0 && socketInfo.secureConnectEndTime > 0) {
            (socketInfo.secureConnectEndTime - socketInfo.secureConnectStartTime) / 1_000_000
        } else -1

        val totalTime = if (request.writeStart > 0) {
            (System.nanoTime() - request.writeStart) / 1_000_000
        } else -1

        // 构建上报指标对象
        val metrics = NetworkMetrics(
            requestId = request.requestId,
            url = socketInfo.url ?: "unknown",
            method = request.method ?: "unknown",
            startTime = request.writeStart / 1_000_000,
            totalTime = totalTime,
            dnsTime = dnsTime,
            connectTime = connectTime,
            sslTime = sslTime,
            ttfb = request.ttfb,
            requestSize = request.requestSize,
            responseSize = request.responseSize,
            success = request.responseCode in 200..299,
            responseCode = request.responseCode,
            errorMessage = socketInfo.errorMessage,
            networkEnv = NetworkMonitor.getNetworkEnv(),
            deviceInfo = NetworkMonitor.getDeviceInfo()
        )

        // 调用上报接口
        NetworkMonitor.report(metrics)
        Log.i(TAG, "上报请求: ${request.requestId}, 总耗时: ${totalTime}ms, 响应码: ${request.responseCode}")
    }

    // 其他方法委托给原始流
    override fun close() = delegate.close()
    override fun available() = delegate.available()
    override fun skip(n: Long) = delegate.skip(n)
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    override fun reset() = delegate.reset()
    override fun markSupported() = delegate.markSupported()
}