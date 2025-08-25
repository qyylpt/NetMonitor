package com.qihoo.net.monitor

import okhttp3.Call
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MetricsHolder {
    private const val METRICS_TYPE_DNS_TIME = "dns_time"
    private const val METRICS_TYPE_CONNECT_TIME = "connect_time"
    private const val METRICS_TYPE_SSL_TIME = "SSL_time"
    private const val METRICS_TYPE_TTFB_TIME = "ttfb_time"
    private const val METRICS_TYPE_RESPONSE_CODE = "response_code"
    private const val METRICS_TYPE_SUCCESSES_STATE = "successes_state"
    private const val METRICS_TYPE_ERROR_MSG = "error_msg"
    private const val METRICS_TYPE_REQUEST_SIZE = "request_size"
    private const val METRICS_TYPE_RESPONSE_SIZE = "response_size"
    private const val METRICS_TYPE_RETRY_COUNT = "retry_count"

    private val metricsMap = ConcurrentHashMap<String, MutableMap<String, Any>>()

    // 存储 Call 实例与 requestId 的映射（key: Call实例，value: 唯一请求ID）
    private val callToRequestId = ConcurrentHashMap<Call, String>()

    /**
     * 获取 Call 对应的 requestId（首次调用时自动生成）
     */
    fun getRequestId(call: Call): String {
        // 线程安全：若不存在则生成 UUID 并存储，存在则直接返回
        return callToRequestId.getOrPut(call) {
            generateRequestId()
        }
    }

    /**
     * 从映射表中移除 Call（请求结束后清理，避免内存泄漏）
     */
    fun removeCall(call: Call) {
        callToRequestId.remove(call)
    }

    /**
     * 生成唯一 requestId（UUID 确保全局唯一）
     */
    private fun generateRequestId(): String {
        return "REQ-${System.currentTimeMillis()}-${UUID.randomUUID()}"
    }

    private fun getMetricsMap(requestId: String): MutableMap<String, Any> {
        return metricsMap.getOrPut(requestId) { mutableMapOf() }
    }

    fun setDnsTime(requestId: String, time: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_DNS_TIME] = time
    }

    fun getDnsTime(requestId: String): Long {
        val dnsTime = getMetricsMap(requestId)[METRICS_TYPE_DNS_TIME] ?: return -1L
        if (dnsTime is Long) {
            return dnsTime
        }
        return -1L
    }

    fun setConnectTime(requestId: String, time: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_CONNECT_TIME] = time
    }

    fun getConnectTime(requestId: String): Long {
        val connectTime = getMetricsMap(requestId)[METRICS_TYPE_CONNECT_TIME] ?: return -1L
        if (connectTime is Long) {
            return connectTime
        }
        return -1L
    }

    fun setSslTime(requestId: String, time: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_SSL_TIME] = time
    }

    fun getSslTime(requestId: String): Long {
        val sslTime = getMetricsMap(requestId)[METRICS_TYPE_SSL_TIME] ?: return -1L
        if (sslTime is Long) {
            return sslTime
        }
        return -1L
    }

    fun setTtfb(requestId: String, time: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_TTFB_TIME] = time
    }

    fun getTtfb(requestId: String): Long {
        val ttfbTime = getMetricsMap(requestId)[METRICS_TYPE_TTFB_TIME] ?: return -1L
        if (ttfbTime is Long) {
            return ttfbTime
        }
        return -1L
    }

    fun setResponseCode(requestId: String, code: Int) {
        getMetricsMap(requestId)[METRICS_TYPE_RESPONSE_CODE] = code
    }

    fun getResponseCode(requestId: String): Int {
        val responseCode = getMetricsMap(requestId)[METRICS_TYPE_RESPONSE_CODE] ?: return -1
        if (responseCode is Int) {
            return responseCode
        }
        return -1
    }

    fun setSuccessState(requestId: String, successState: Boolean) {
        getMetricsMap(requestId)[METRICS_TYPE_SUCCESSES_STATE] = successState
    }

    fun getSuccessState(requestId: String): Boolean {
        val successState = getMetricsMap(requestId)[METRICS_TYPE_SUCCESSES_STATE] ?: return false
        if (successState is Boolean) {
            return successState
        }
        return false
    }

    fun setErrorMsg(requestId: String, errorMsg: String) {
        getMetricsMap(requestId)[METRICS_TYPE_ERROR_MSG] = errorMsg
    }

    fun getErrorMsg(requestId: String): String {
        val errorMsg = getMetricsMap(requestId)[METRICS_TYPE_ERROR_MSG] ?: return ""
        if (errorMsg is String) {
            return errorMsg
        }
        return ""
    }

    fun setRequestSize(requestId: String, requestSize: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_REQUEST_SIZE] = requestSize
    }

    fun getRequestSize(requestId: String): Long {
        val requestSize = getMetricsMap(requestId)[METRICS_TYPE_REQUEST_SIZE] ?: return -1L
        if (requestSize is Long) {
            return requestSize
        }
        return -1L
    }

    fun setResponseSize(requestId: String, responseSize: Long) {
        getMetricsMap(requestId)[METRICS_TYPE_RESPONSE_SIZE] = responseSize
    }

    fun getResponseSize(requestId: String): Long {
        val responseSize = getMetricsMap(requestId)[METRICS_TYPE_RESPONSE_SIZE] ?: return -1L
        if (responseSize is Long) {
            return responseSize
        }
        return -1L
    }

    fun setRetryCount(requestId: String, count: Int) {
        getMetricsMap(requestId)[METRICS_TYPE_RETRY_COUNT] = count
    }

    fun getRetryCount(requestId: String): Int {
        val retryCount = getMetricsMap(requestId)[METRICS_TYPE_RETRY_COUNT] ?: return -1
        if (retryCount is Int) {
            return retryCount
        }
        return -1
    }

}