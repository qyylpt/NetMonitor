package com.qihoo.net.monitor

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log

class NetworkSignalHelper(context: Context) {
    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var currentSignalStrength: Int? = null // 信号强度值（dBm）
    private var isReleased = false // 标记是否已释放

    // 直接初始化信号监听器（解决未初始化问题）
    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            super.onSignalStrengthsChanged(signalStrength)

            // 获取CELLULAR信号强度（dBm）
            currentSignalStrength = when {
                // Android 11+（API 30+）新API
                android.os.Build.VERSION.SDK_INT >= 30 -> {
                    signalStrength.cellSignalStrengths.firstOrNull()?.dbm
                }
                // 旧版本API
                else -> {
                    @Suppress("DEPRECATION")
                    val gsmSignal = signalStrength.gsmSignalStrength
                    if (gsmSignal != 99) -(113 - 2 * gsmSignal) else null
                }
            }

            Log.d("SignalStrength", "当前信号强度: $currentSignalStrength dBm")
        }
    }

    init {
        // 注册信号强度监听器（在init块中使用已初始化的signalListener）
        telephonyManager.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    // 获取当前信号强度（dBm）
    fun getCurrentCellularSignalStrength(): Int? {
        return currentSignalStrength
    }

    // 释放资源
    fun release() {
        if (isReleased) {
            return
        }
        telephonyManager.listen(signalListener, PhoneStateListener.LISTEN_NONE)
        isReleased = true
    }

    fun isReleased() : Boolean {
        return isReleased
    }
}