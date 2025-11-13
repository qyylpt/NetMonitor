package com.qihoo.net.monitor.socket

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * 包装输入流，统计响应数据大小并解析HTTP响应头
 */
class MonitoredInputStream(
    private val delegate: InputStream,
    private val onBytesRead: (Long) -> Unit
) : InputStream() {
    private val TAG = "MonitoredInputStream"
    private val headerBuffer = StringBuilder()
    private var isHeaderComplete = false
    private var isFirstRead = true

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) {
            onBytesRead(1)
            processHeaderByte(b.toByte())
        }
        return b
    }

    override fun read(b: ByteArray): Int {
        val len = delegate.read(b)
        if (len != -1) {
            onBytesRead(len.toLong())
            if (!isHeaderComplete) {
                processHeaderBytes(b, 0, len)
            }
        }
        return len
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val readLen = delegate.read(b, off, len)
        if (readLen != -1) {
            onBytesRead(readLen.toLong())
            if (!isHeaderComplete) {
                processHeaderBytes(b, off, readLen)
            }
        }
        return readLen
    }

    private fun processHeaderByte(b: Byte) {
        if (isHeaderComplete) return

        if (isFirstRead) {
            isFirstRead = false
            Log.d(TAG, "首次读取响应数据")
        }

        // 添加到头部缓冲区
        headerBuffer.append(b.toInt().toChar())

        // 检查头部是否结束（空行）
        if (headerBuffer.length >= 4) {
            val lastFour = headerBuffer.substring(headerBuffer.length - 4)
            if (lastFour == "\r\n\r\n") {
                isHeaderComplete = true
                parseHttpResponseHeader(headerBuffer.toString())
            }
        }
    }

    private fun processHeaderBytes(b: ByteArray, off: Int, len: Int) {
        if (isHeaderComplete) return

        if (isFirstRead) {
            isFirstRead = false
            Log.d(TAG, "首次读取响应数据")
        }

        // 逐字节处理，查找HTTP头结束位置
        for (i in 0 until len) {
            processHeaderByte(b[off + i])
            if (isHeaderComplete) break
        }
    }

    private fun parseHttpResponseHeader(headerStr: String) {
        try {
            Log.d(TAG, "解析HTTP响应头: \n$headerStr")

            val lines = headerStr.split("\r\n")
            if (lines.isNotEmpty()) {
                // 解析状态行
                val statusLine = lines[0]
                val parts = statusLine.split(" ", limit = 3)
                if (parts.size >= 2) {
                    val responseCode = parts[1].toIntOrNull() ?: -1
                    Log.d(TAG, "HTTP响应码: $responseCode")

                    // 这里应该将响应码存储到对应的Socket信息中
                    // 但由于流与Socket的关系需要在创建流时建立，这里简化处理
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析HTTP响应头失败", e)
        }
    }

    override fun close() = delegate.close()
    override fun available() = delegate.available()
    override fun skip(n: Long) = delegate.skip(n)
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    override fun reset() = delegate.reset()
    override fun markSupported() = delegate.markSupported()
}

/**
 * 包装输出流，统计请求数据大小并解析HTTP请求头
 */
class MonitoredOutputStream(
    private val delegate: OutputStream,
    private val onBytesWritten: (Long) -> Unit
) : OutputStream() {
    private val TAG = "MonitoredOutputStream"
    private val headerBuffer = StringBuilder()
    private var isHeaderComplete = false
    private var isFirstWrite = true

    override fun write(b: Int) {
        delegate.write(b)
        onBytesWritten(1)
        processHeaderByte(b.toByte())
    }

    override fun write(b: ByteArray) {
        delegate.write(b)
        onBytesWritten(b.size.toLong())
        processHeaderBytes(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        onBytesWritten(len.toLong())
        processHeaderBytes(b, off, len)
    }

    private fun processHeaderByte(b: Byte) {
        if (isHeaderComplete) return

        if (isFirstWrite) {
            isFirstWrite = false
            Log.d(TAG, "首次写入请求数据")
        }

        // 添加到头部缓冲区
        headerBuffer.append(b.toInt().toChar())

        // 检查头部是否结束（空行）
        if (headerBuffer.length >= 4) {
            val lastFour = headerBuffer.substring(headerBuffer.length - 4)
            if (lastFour == "\r\n\r\n") {
                isHeaderComplete = true
                parseHttpRequestHeader(headerBuffer.toString())
            }
        }
    }

    private fun processHeaderBytes(b: ByteArray, off: Int, len: Int) {
        if (isHeaderComplete) return

        if (isFirstWrite) {
            isFirstWrite = false
            Log.d(TAG, "首次写入请求数据")
        }

        // 逐字节处理，查找HTTP头结束位置
        for (i in 0 until len) {
            processHeaderByte(b[off + i])
            if (isHeaderComplete) break
        }
    }

    private fun parseHttpRequestHeader(headerStr: String) {
        try {
            Log.d(TAG, "解析HTTP请求头: \n$headerStr")

            val lines = headerStr.split("\r\n")
            if (lines.isNotEmpty()) {
                // 解析请求行
                val requestLine = lines[0]
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size >= 2) {
                    val method = parts[0]
                    val path = parts[1]
                    Log.d(TAG, "HTTP请求: $method $path")

                    // 解析Host头，构建完整URL
                    var host = ""
                    for (i in 1 until lines.size) {
                        if (lines[i].startsWith("Host:", ignoreCase = true)) {
                            host = lines[i].substring(5).trim()
                            break
                        }
                    }

                    if (host.isNotEmpty()) {
                        val scheme = if (host.contains(":443")) "https" else "http"
                        val url = "$scheme://$host$path"
                        Log.d(TAG, "完整URL: $url")

                        // 这里应该将方法和URL存储到对应的Socket信息中
                        // 但由于流与Socket的关系需要在创建流时建立，这里简化处理
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析HTTP请求头失败", e)
        }
    }

    override fun close() = delegate.close()
    override fun flush() = delegate.flush()
}