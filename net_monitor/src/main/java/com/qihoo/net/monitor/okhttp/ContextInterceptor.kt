package com.qihoo.net.monitor.okhttp

import com.qihoo.net.monitor.MetricsHolder
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 在请求生命周期内绑定/清理 Call 与 requestId 的映射
 */
class ContextInterceptor: Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // 获取当前请求的 Call 实例
        val call = chain.call()

        try {
            // 触发 requestId 生成（首次调用时绑定）
            MetricsHolder.getRequestId(call)
            // 执行请求（继续拦截器链）
            return chain.proceed(chain.request())
        } finally {
            // 无论请求成功/失败，都清理映射关系（关键：避免内存泄漏）
            MetricsHolder.removeCall(call)
        }
    }
}