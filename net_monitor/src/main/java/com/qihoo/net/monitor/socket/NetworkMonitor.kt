// src/main/kotlin/com/qihoo/net/monitor/NetworkMonitor.kt
package com.qihoo.net.monitor.socket

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log

object NetworkMonitor {
    private const val TAG = "NetworkMonitor"
    private var isInitialized = false
    private lateinit var appContext: Context
    private val listeners = mutableListOf<NetworkMetricsListener>()

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true

        try {
            // 初始化Socket监控
            SocketHook.init()
            Log.i(TAG, "NetworkMonitor 初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "NetworkMonitor 初始化失败", e)
        }
    }

    fun addListener(listener: NetworkMetricsListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NetworkMetricsListener) {
        listeners.remove(listener)
    }

    fun report(metrics: NetworkMetrics) {
        Log.d(TAG, "上报网络指标: $metrics")
        listeners.forEach { it.onMetricsCollected(metrics) }
    }

    fun getNetworkEnv(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetworkInfo
        return when {
            activeNetwork == null -> "unknown"
            activeNetwork.type == ConnectivityManager.TYPE_WIFI -> "wifi"
            activeNetwork.type == ConnectivityManager.TYPE_MOBILE -> "mobile"
            else -> "other"
        }
    }

    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }
}