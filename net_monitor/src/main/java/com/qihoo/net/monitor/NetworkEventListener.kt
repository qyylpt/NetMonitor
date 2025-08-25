package com.qihoo.net.monitor

import android.content.Context
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class NetworkEventListener(private val context: Context) : EventListener() {
    private var requestId: String = ""
    private var startTime: Long = 0
    private var dnsStartTime: Long = 0
    private var connectStartTime: Long = 0
    private var secureConnectStartTime: Long = 0
    private var firstByteTime: Long = 0
    private var success: Boolean = true
    private var responseCode: Int = -1
    private var errorMessage: String? = null
    private var redirectCount = 0
    private var previousRequestUrl: String? = null

    class Factory(private val context: Context) : EventListener.Factory {
        override fun create(call: Call): EventListener {
            return NetworkEventListener(context)
        }
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        // 记录请求体大小
        MetricsHolder.setRequestSize(requestId, byteCount)
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        // 记录响应体大小
        MetricsHolder.setResponseSize(requestId, byteCount)
    }


    override fun callStart(call: Call) {
        startTime = System.currentTimeMillis()
        dnsStartTime = 0
        connectStartTime = 0
        secureConnectStartTime = 0
        firstByteTime = 0
        success = true
        responseCode = -1
        errorMessage = null
        redirectCount = 0
        previousRequestUrl = null
        requestId = MetricsHolder.getRequestId(call)
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartTime = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val dnsTime = if (dnsStartTime > 0) {
            (System.nanoTime() - dnsStartTime) / 1000000 // 手动转换纳秒到毫秒
        } else -1
        MetricsHolder.setDnsTime(requestId, dnsTime)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartTime = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStartTime = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        val sslTime = if (secureConnectStartTime > 0) {
            (System.nanoTime() - secureConnectStartTime) / 1000000
        } else -1
        MetricsHolder.setSslTime(requestId, sslTime)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        val connectTime = if (connectStartTime > 0) {
            (System.nanoTime() - connectStartTime) / 1000000
        } else -1
        MetricsHolder.setConnectTime(requestId, connectTime)
    }

    override fun responseHeadersStart(call: Call) {
        firstByteTime = System.currentTimeMillis()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val ttfb = if (startTime > 0 && firstByteTime > 0) {
            firstByteTime - startTime
        } else -1

        responseCode = response.code()
        success = response.isSuccessful

        MetricsHolder.setTtfb(requestId, ttfb)
        MetricsHolder.setResponseCode(requestId, responseCode)
        MetricsHolder.setSuccessState(requestId, success)
    }

    // 兼容 OkHttp 3.x：通过 requestHeadersEnd 检测重定向
    override fun requestHeadersEnd(call: Call, request: Request) {
        val currentUrl = request.url().toString()
        if (previousRequestUrl != null && currentUrl != previousRequestUrl) {
            redirectCount++
        }
        previousRequestUrl = currentUrl
        MetricsHolder.setRetryCount(requestId, redirectCount)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        success = false
        errorMessage = ioe.message
        MetricsHolder.setSuccessState(requestId, false)
        errorMessage?.let { MetricsHolder.setErrorMsg(requestId, it) }
        reportMetrics(call, -1)
    }

    override fun callEnd(call: Call) {
        val totalTime = System.currentTimeMillis() - startTime
        reportMetrics(call, totalTime)
    }

    private fun reportMetrics(call: Call, totalTime: Long) {
        val dnsTime = MetricsHolder.getDnsTime(requestId)
        val connectTime = MetricsHolder.getConnectTime(requestId)
        val sslTime = MetricsHolder.getSslTime(requestId)
        val ttfb = MetricsHolder.getTtfb(requestId)
        val success = MetricsHolder.getSuccessState(requestId)
        val responseCode = MetricsHolder.getResponseCode(requestId)
        val error = MetricsHolder.getErrorMsg(requestId)
        val requestSize = MetricsHolder.getRequestSize(requestId)
        val responseSize = MetricsHolder.getResponseSize(requestId)
        val retryCount = MetricsHolder.getRetryCount(requestId)
        val metrics = NetworkMetrics(
            requestId = requestId,
            url = call.request().url().toString(),
            method = call.request().method(),
            startTime = startTime,
            totalTime = totalTime,
            dnsTime = dnsTime,
            connectTime = connectTime,
            sslTime = sslTime,
            ttfb = ttfb,
            requestSize = requestSize,
            responseSize = responseSize,
            success = success,
            responseCode = responseCode,
            errorMessage = error,
            networkEnv = NetworkMonitor.getNetworkEnv(context),
            deviceInfo = NetworkMonitor.getDeviceInfo(context),
            retryCount = retryCount,
            redirectCount = redirectCount
        )

        NetworkMonitor.report(metrics)
        MetricsHolder.removeCall(call = call)
    }
}