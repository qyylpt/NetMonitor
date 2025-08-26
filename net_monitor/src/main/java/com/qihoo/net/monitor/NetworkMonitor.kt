package com.qihoo.net.monitor

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

object NetworkMonitor {

    private var networkSignalHelper: NetworkSignalHelper? = null
    private var application: Application? = null

    fun init(application: Application) {
        if (networkSignalHelper == null) {
            networkSignalHelper = NetworkSignalHelper(application.applicationContext)
        }
        this.application = application
    }

    // 上报指标
    fun report(metrics: NetworkMetrics) {
        Log.d("NetworkMonitor", "Metrics: $metrics; ${Thread.currentThread().id}}")
    }

    // 获取设备信息
    internal fun getDeviceInfo(): DeviceInfo? {
        if (application == null) {
            return null
        }
        val packageInfo = try {
            application!!.packageManager.getPackageInfo(application!!.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        return DeviceInfo(
            appVersion = packageInfo?.versionName ?: "Unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            deviceMemory = getTotalMemory(application!!)
        )
    }

    // 获取设备总内存
    private fun getTotalMemory(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024) // MB
    }

    // 获取网络环境信息
    internal fun getNetworkEnv(): NetworkEnv {
        val cm = application?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NetworkEnv("UNKNOWN", null, null)
            val capabilities = cm.getNetworkCapabilities(network)
                ?: return NetworkEnv("UNKNOWN", null, null)
            val networkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "UNKNOWN"
            }
            val signalStrength = when (networkType) {
                "WIFI" -> {
                    val wifiManager = application?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiManager.connectionInfo.rssi
                }

                "CELLULAR" -> {
                    networkSignalHelper?.getCurrentCellularSignalStrength()
                }

                else -> -1
            }
            val carrier = if (networkType == "CELLULAR") {
                val telephonyManager = application?.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val simOperator = telephonyManager.simOperator
                if ("46001" == simOperator || "46006" == simOperator || "46009" == simOperator) {
                    "中国联通"
                } else if ("46000" == simOperator || "46002" == simOperator || "46004" == simOperator || "46007" == simOperator) {
                    "中国移动"
                } else if ("46003" == simOperator || "46005" == simOperator || "46011" == simOperator) {
                    "中国电信"
                } else if ("46020" == simOperator) {
                    "中国铁通"
                } else {
                    "OHTER"
                }
            } else "UNKNOWN"
            return NetworkEnv(networkType, signalStrength, carrier)
        }
        return NetworkEnv("", -1, "")
    }
}