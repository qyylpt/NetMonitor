package com.qihoo.net.monitor.hook

import MonitoredInputStream
import android.util.Log
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

object SocketMonitor {
    private const val TAG = "SocketMonitor"
    private val scheduler = Executors.newScheduledThreadPool(1) // 定时扫描超时请求

    fun init() {
        try {
            Pine.ensureInitialized()
            PineConfig.debug = true

            // 1. Hook Socket 构造函数
            hookSocketConstructor()
            // 2. Hook 连接方法
            hookSocketConnect()
            // 3. Hook 关闭方法
            hookSocketClose()
            // 4. Hook 输入输出流
            hookSocketStreams()
            // 5. Hook DNS 解析
            hookDnsResolution()
            // 6. Hook SSL 连接
            hookSSLSocket()
            // 7. 启动超时扫描任务（每30秒一次）
            startTimeoutChecker()

            Log.i(TAG, "Socket 监控初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "Socket 监控初始化失败", e)
        }
    }

    // 1. Hook Socket 构造函数
    private fun hookSocketConstructor() {
        Pine.hook(Socket::class.java.getDeclaredConstructor(), object : MethodHook() {
            override fun afterCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                val info = MetricsHolder.getOrCreateSocketInfo(socket)
                info.connectStartTime = System.nanoTime()
                Log.d(TAG, "Socket 创建: ${socket.hashCode()}, socketId: ${info.socketId}")
            }
        })
    }

    // 2. Hook Socket 连接方法
    private fun hookSocketConnect() {
        // 连接方法1：connect(SocketAddress)
        val connect1 = Socket::class.java.getDeclaredMethod("connect", SocketAddress::class.java)
        Pine.hook(connect1, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                val address = callFrame.args[0] as InetSocketAddress
                val info = MetricsHolder.getOrCreateSocketInfo(socket)
                info.host = address.hostName
                info.port = address.port
                info.url = "${if (info.isSSL) "https" else "http"}://${info.host}:${info.port}"
                Log.d(TAG, "Socket 连接开始: ${info.socketId}, 地址: ${info.host}:${info.port}")
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                val info = MetricsHolder.getSocketInfo(socket) ?: return
                if (callFrame.throwable == null) {
                    info.connectEndTime = System.nanoTime()
                    val connectTime = (info.connectEndTime - info.connectStartTime) / 1_000_000
                    Log.d(TAG, "Socket 连接完成: ${info.socketId}, 耗时: $connectTime ms")
                } else {
                    info.errorMessage = callFrame.throwable.message
                    Log.e(TAG, "Socket 连接失败: ${info.socketId}, 错误: ${info.errorMessage}")
                }
            }
        })

        // 连接方法2：connect(SocketAddress, Int)
        val connect2 = Socket::class.java.getDeclaredMethod("connect", SocketAddress::class.java, Int::class.java)
        Pine.hook(connect2, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                // 逻辑同 connect1
                val socket = callFrame.thisObject as Socket
                val address = callFrame.args[0] as InetSocketAddress
                val info = MetricsHolder.getOrCreateSocketInfo(socket)
                info.host = address.hostName
                info.port = address.port
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                // 逻辑同 connect1
                val socket = callFrame.thisObject as Socket
                val info = MetricsHolder.getSocketInfo(socket) ?: return
                if (callFrame.throwable == null) {
                    info.connectEndTime = System.nanoTime()
                }
            }
        })
    }

    // 3. Hook Socket 关闭方法
    private fun hookSocketClose() {
        Pine.hook(Socket::class.java.getDeclaredMethod("close"), object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                val info = MetricsHolder.getSocketInfo(socket) ?: return
                // 上报未完成的请求
                info.currentRequest?.takeIf { !it.isCompleted }?.let {
                    reportRequestMetrics(it, info)
                    it.isCompleted = true
                }
                Log.d(TAG, "Socket 关闭: ${info.socketId}, 复用次数: ${info.reuseCount}")
                MetricsHolder.removeSocketInfo(socket)
            }
        })
    }

    // 4. Hook 输入输出流
    private fun hookSocketStreams() {
        // Hook 输入流（响应）
        try {
            val getInputStreamMethod = Socket::class.java.getDeclaredMethod("getInputStream")
            Pine.hook(getInputStreamMethod, object : MethodHook() {
                override fun afterCall(callFrame: Pine.CallFrame) {
                    if (callFrame.result == null) return
                    val socket = callFrame.thisObject as Socket
                    val info = MetricsHolder.getOrCreateSocketInfo(socket) // 确保创建 SocketInfo
                    // 强制替换为包装流
                    val wrappedStream = MonitoredInputStream(callFrame.result as InputStream, info)
                    callFrame.result = wrappedStream
                    Log.d("SocketHook", "InputStream 已包装: ${socket.hashCode()}, socketId: ${info.socketId}")
                }
            })
        } catch (e: Exception) {
            Log.e("SocketHook", "Hook getInputStream 失败", e)
        }

        // Hook 输出流（请求）
        try {
            val getOutputStreamMethod = Socket::class.java.getDeclaredMethod("getOutputStream")
            Pine.hook(getOutputStreamMethod, object : MethodHook() {
                override fun afterCall(callFrame: Pine.CallFrame) {
                    if (callFrame.result == null) return
                    val socket = callFrame.thisObject as Socket
                    val info = MetricsHolder.getOrCreateSocketInfo(socket) // 确保创建 SocketInfo
                    // 强制替换为包装流
                    val wrappedStream = MonitoredOutputStream(callFrame.result as OutputStream, info)
                    callFrame.result = wrappedStream
                    Log.d("SocketHook", "OutputStream 已包装: ${socket.hashCode()}, socketId: ${info.socketId}")
                }
            })
        } catch (e: Exception) {
            Log.e("SocketHook", "Hook getOutputStream 失败", e)
        }
    }

    // 5. Hook DNS 解析
    private fun hookDnsResolution() {
        Pine.hook(InetAddress::class.java.getDeclaredMethod("getAllByName", String::class.java), object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val host = callFrame.args[0] as String
                ThreadLocalStorage.setDnsStart(host, System.nanoTime())
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.throwable != null) return
                val host = callFrame.args[0] as String
                val dnsStart = ThreadLocalStorage.getDnsStart(host) ?: return
                val dnsEnd = System.nanoTime()
                val dnsTime = (dnsEnd - dnsStart) / 1_000_000
                Log.d(TAG, "DNS 解析完成: $host, 耗时: $dnsTime ms")
                ThreadLocalStorage.setDnsEnd(host, dnsEnd)
            }
        })
    }

    // 6. Hook SSL 连接
    private fun hookSSLSocket() {
        // 定义需要 Hook 的具体实现类（优先尝试 Android 主流实现）
        val sslFactoryImplClasses = listOf(
            "com.android.org.conscrypt.OpenSSLSocketFactoryImpl", // 最常见的实现
            "sun.security.ssl.SSLSocketFactoryImpl",
            "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketFactoryImpl"
        )

        // 要 Hook 的方法签名（参数：Socket, String, int, boolean，返回 Socket）
        val methodParams = arrayOf(
            Socket::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )

        for (className in sslFactoryImplClasses) {
            try {
                // 加载具体实现类
                val factoryClass = Class.forName(className)
                // 获取具体实现的 createSocket 方法（非抽象）
                val createMethod = factoryClass.getDeclaredMethod(
                    "createSocket",
                    *methodParams
                )

                // 检查方法是否为抽象方法（避免重复错误）
                if (!Modifier.isAbstract(createMethod.modifiers)) {
                    Pine.hook(createMethod, object : MethodHook() {
                        override fun beforeCall(callFrame: Pine.CallFrame) {
                            val plainSocket = callFrame.args[0] as? Socket ?: return
                            val host = callFrame.args[1] as? String ?: return
                            val info = MetricsHolder.getSocketInfo(plainSocket) ?: return

                            // 记录 SSL 握手开始时间
                            info.secureConnectStartTime = System.nanoTime()
                            info.isSSL = true
                            Log.d(TAG, "SSL 握手开始: ${info.socketId}, host: $host")
                        }

                        override fun afterCall(callFrame: Pine.CallFrame) {
                            if (callFrame.throwable != null || callFrame.result !is SSLSocket) return

                            val sslSocket = callFrame.result as SSLSocket
                            val plainSocket = callFrame.args[0] as? Socket ?: return
                            val plainInfo = MetricsHolder.getSocketInfo(plainSocket) ?: return

                            // 复制原始 Socket 信息到 SSL Socket
                            val sslInfo = MetricsHolder.getOrCreateSocketInfo(sslSocket)
                            sslInfo.host = plainInfo.host
                            sslInfo.port = plainInfo.port
                            sslInfo.isSSL = true
                            sslInfo.secureConnectStartTime = plainInfo.secureConnectStartTime
                            sslInfo.connectStartTime = plainInfo.connectStartTime
                            sslInfo.connectEndTime = plainInfo.connectEndTime
                            sslInfo.url = plainInfo.url?.replace("http://", "https://")

                            // 监听 SSL 握手完成事件
                            sslSocket.addHandshakeCompletedListener { event ->
                                sslInfo.secureConnectEndTime = System.nanoTime()
                                val sslTime = (sslInfo.secureConnectEndTime - sslInfo.secureConnectStartTime) / 1_000_000
                                Log.d(TAG, "SSL 握手完成: ${sslInfo.socketId}, 耗时: $sslTime ms")
                            }
                        }
                    })
                    Log.d(TAG, "成功 Hook SSL 实现类: $className")
                    return // 成功 Hook 一个实现类即可，无需继续尝试
                }
            } catch (e: ClassNotFoundException) {
                // 该实现类不存在，尝试下一个
                Log.d(TAG, "未找到 SSL 实现类: $className")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "类 $className 中未找到目标方法")
            } catch (e: Exception) {
                Log.e(TAG, "Hook 类 $className 失败", e)
            }
        }

        Log.w(TAG, "未找到可 Hook 的 SSLSocketFactory 实现类，SSL 监控功能将不可用")
    }

    // 7. 启动超时扫描任务（处理长期未关闭的连接）
    private fun startTimeoutChecker() {
        scheduler.scheduleWithFixedDelay({
            val timeoutNs = 30_000_000_000 // 30秒超时
            val now = System.nanoTime()
            MetricsHolder.getActiveRequests().forEach { request ->
                val socketInfo = MetricsHolder.socketInfoMap.values.find { it.currentRequest?.requestId == request.requestId }
                if (socketInfo != null && (now - socketInfo.lastActivityTime) > timeoutNs) {
                    // 超时未活动，强制上报
                    reportRequestMetrics(request, socketInfo)
                    request.isCompleted = true
                    socketInfo.currentRequest = null
                    MetricsHolder.removeActiveRequest(request.requestId)
                    Log.d(TAG, "请求超时上报: ${request.requestId}")
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
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

        // 实际上报逻辑（此处简化）
        NetworkMonitor.report(metrics)
        Log.i(TAG, "请求上报: ${request.requestId}, 总耗时: ${metrics.totalTime} ms, 响应码: ${metrics.responseCode}")
    }
}

// 线程本地存储（关联 DNS 解析和 Socket）
object ThreadLocalStorage {
    private val threadLocal = ThreadLocal<MutableMap<String, Long>>()

    private fun getMap(): MutableMap<String, Long> {
        return threadLocal.get() ?: mutableMapOf<String, Long>().also { threadLocal.set(it) }
    }

    fun setDnsStart(host: String, time: Long) {
        getMap()["dns_start_$host"] = time
    }

    fun getDnsStart(host: String): Long? {
        return getMap()["dns_start_$host"]
    }

    fun setDnsEnd(host: String, time: Long) {
        getMap()["dns_end_$host"] = time
    }

    fun getDnsEnd(host: String): Long? {
        return getMap()["dns_end_$host"]
    }
}

// 网络环境与设备信息工具类（示例）
object NetworkMonitor {
    fun getNetworkEnv(): String = "wifi" // 实际应获取真实网络类型
    fun getDeviceInfo(): String = "Android 13" // 实际应获取设备信息
    fun report(metrics: NetworkMetrics) {
        // 上报逻辑（如发送到服务器）
        Log.d("zsf_test", "report() -> $metrics")
    }
}