package com.qihoo.net.monitor.socket

import android.util.Log
import com.qihoo.net.monitor.BuildConfig
import top.canyie.pine.PineConfig
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object SocketHook {
    private const val TAG = "SocketHook"

    fun init() {
        try {
            // 确保Pine初始化
            Pine.ensureInitialized()
            // 启用Pine调试日志
            PineConfig.debug = true
            PineConfig.debuggable = BuildConfig.DEBUG  // 与应用调试状态保持一致

            // 1. Hook Socket构造函数 - 具体方法，不是抽象方法
            Pine.hook(Socket::class.java.getDeclaredConstructor(), object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    // 构造函数前不需要处理
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    val socket = callFrame.thisObject as Socket
                    val info = MetricsHolder.getOrCreateSocketInfo(socket)
                    info.connectStartTime = System.nanoTime()
                    Log.d(TAG, "Socket创建: ${socket.hashCode()}, requestId: ${info.requestId}")
                }
            })

            // 2. Hook Socket.connect方法 - 确保hook具体实现而不是抽象方法
            hookSocketConnectMethods()

            // 3. Hook Socket关闭方法
            hookSocketClose()

            // 4. Hook Socket输入输出流
            hookSocketStreams()

            // 5. Hook DNS解析
            hookDnsResolution()

            // 6. Hook SSL连接
            hookSSLSocket()

            Log.i(TAG, "Socket Hook初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "Socket Hook初始化失败", e)
        }
    }

    /**
     * 优化的connect方法hook：hook具体实现而不是抽象方法
     */
    private fun hookSocketConnectMethods() {
        // Hook java.net.Socket的具体connect实现
        try {
            // Hook Socket类的connect(SocketAddress)方法
            val connectMethod = Socket::class.java.getDeclaredMethod("connect", SocketAddress::class.java)
            if (!Modifier.isAbstract(connectMethod.modifiers)) {
                hookConnectMethod(connectMethod)
            }

            // Hook Socket类的connect(SocketAddress, int)方法
            val connectWithTimeoutMethod = Socket::class.java.getDeclaredMethod(
                "connect",
                SocketAddress::class.java,
                Int::class.java
            )
            hookConnectMethod(connectWithTimeoutMethod)

            // 查找并hook常用Socket实现类的connect方法
            val socketImplClass = Class.forName("java.net.SocksSocketImpl")
            try {
                val implConnectMethod = socketImplClass.getDeclaredMethod(
                    "connect",
                    SocketAddress::class.java,
                    Int::class.java
                )
                hookConnectMethod(implConnectMethod)
            } catch (e: Exception) {
                Log.w(TAG, "无法hook SocksSocketImpl.connect方法", e)
            }

            // 其他可能的Socket实现类...

        } catch (e: Exception) {
            Log.e(TAG, "Hook Socket connect方法失败", e)
        }
    }

    private fun hookConnectMethod(method: java.lang.reflect.Method) {
        Pine.hook(method, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as? Socket ?: return
                val address = callFrame.args[0] as? SocketAddress ?: return
                val info = MetricsHolder.getOrCreateSocketInfo(socket)

                if (address is InetSocketAddress) {
                    info.host = address.hostName
                    info.port = address.port

                    // 记录DNS解析开始
                    if (!address.isUnresolved) {
                        info.dnsStartTime = System.nanoTime()

                        // 推测URL
                        val scheme = if (info.port == 443) "https" else "http"
                        info.url = "$scheme://${info.host}:${info.port}"
                    }
                }

                Log.d(TAG, "Socket连接开始: ${socket.hashCode()}, " +
                        "地址: ${info.host}:${info.port}, requestId: ${info.requestId}")
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.throwable != null) {
                    // 连接失败，记录错误
                    val socket = callFrame.thisObject as? Socket ?: return
                    val info = MetricsHolder.getSocketInfo(socket) ?: return
                    info.errorMessage = callFrame.throwable.message
                    Log.e(TAG, "Socket连接失败: ${socket.hashCode()}, ${callFrame.throwable.message}")
                    return
                }

                val socket = callFrame.thisObject as? Socket ?: return
                val info = MetricsHolder.getSocketInfo(socket) ?: return

                // 记录TCP连接完成时间
                info.connectEndTime = System.nanoTime()

                val connectTime = (info.connectEndTime - info.connectStartTime) / 1_000_000
                Log.d(TAG, "Socket连接完成: ${socket.hashCode()}, " +
                        "耗时: $connectTime ms, requestId: ${info.requestId}")

                // 对于普通Socket，连接完成后可以计算DNS时间
                if (info.dnsStartTime > 0 && info.dnsEndTime == 0L) {
                    // DNS已经完成，记录结束时间
                    info.dnsEndTime = info.connectStartTime + 1  // 设置一个近似值
                }
            }
        })
    }

    private fun hookSocketClose() {
        // Hook Socket.close方法，收集完整统计信息并上报
        Pine.hook(Socket::class.java.getDeclaredMethod("close"), object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                val info = MetricsHolder.getSocketInfo(socket) ?: return

                // 收集最终数据并上报
                reportSocketMetrics(socket, info)

                Log.d(TAG, "Socket关闭: ${socket.hashCode()}, requestId: ${info.requestId}")
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                val socket = callFrame.thisObject as Socket
                // 清理资源
                MetricsHolder.removeSocketInfo(socket)
            }
        })
    }

    private fun hookSocketStreams() {
        // Hook Socket.getInputStream，包装输入流以测量响应大小
        Pine.hook(Socket::class.java.getDeclaredMethod("getInputStream"), object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                // 不需要在调用前处理
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.throwable != null || callFrame.result == null) return

                val socket = callFrame.thisObject as Socket
                val originalStream = callFrame.result as InputStream
                val info = MetricsHolder.getSocketInfo(socket) ?: return

                try {
                    // 创建包装流
                    val monitoredStream = MonitoredInputStream(originalStream) { bytesRead ->
                        info.totalResponseSize += bytesRead

                        // 第一个字节到达，记录TTFB
                        if (info.responseHeadersEndTime == 0L && info.connectEndTime > 0) {
                            info.responseHeadersEndTime = System.nanoTime()
                            val ttfb = (info.responseHeadersEndTime - info.connectEndTime) / 1_000_000
                            Log.d(TAG, "Socket TTFB: ${socket.hashCode()}, " +
                                    "耗时: $ttfb ms, requestId: ${info.requestId}")
                        }
                    }

                    // 替换原始流
                    callFrame.result = monitoredStream
                } catch (e: Exception) {
                    Log.e(TAG, "包装InputStream失败", e)
                }
            }
        })

        // Hook Socket.getOutputStream，包装输出流以测量请求大小
        Pine.hook(Socket::class.java.getDeclaredMethod("getOutputStream"), object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                // 不需要在调用前处理
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.throwable != null || callFrame.result == null) return

                val socket = callFrame.thisObject as Socket
                val originalStream = callFrame.result as OutputStream
                val info = MetricsHolder.getSocketInfo(socket) ?: return

                try {
                    // 创建包装流
                    val monitoredStream = MonitoredOutputStream(originalStream) { bytesWritten ->
                        info.totalRequestSize += bytesWritten
                    }

                    // 替换原始流
                    callFrame.result = monitoredStream
                } catch (e: Exception) {
                    Log.e(TAG, "包装OutputStream失败", e)
                }
            }
        })
    }

    private fun hookDnsResolution() {
        // Hook InetAddress.getAllByName - 具体实现，不是抽象方法
        Pine.hook(InetAddress::class.java.getDeclaredMethod("getAllByName", String::class.java),
            object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    val host = callFrame.args[0] as? String ?: return
                    Log.d(TAG, "DNS解析开始: $host")

                    // 给当前线程存储开始时间和主机名，用于后续关联到Socket
                    val threadLocal = ThreadLocalStorage.getOrCreate()
                    threadLocal.dnsLookupStart = System.nanoTime()
                    threadLocal.dnsHost = host
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    if (callFrame.throwable != null || callFrame.result == null) return

                    val host = callFrame.args[0] as? String ?: return
                    val addresses = callFrame.result as? Array<*> ?: return
                    val threadLocal = ThreadLocalStorage.getOrCreate()

                    if (threadLocal.dnsLookupStart > 0) {
                        val dnsTime = (System.nanoTime() - threadLocal.dnsLookupStart) / 1_000_000
                        Log.d(TAG, "DNS解析完成: $host -> ${addresses.size}个结果, 耗时: $dnsTime ms")

                        // 存储DNS解析结果供Socket使用
                        threadLocal.dnsEndTime = System.nanoTime()
                        threadLocal.dnsTime = dnsTime
                    }
                }
            })
    }

    private fun hookSSLSocket() {
        // Hook SSLSocketFactory.createSocket - 确保hook具体实现
        try {
            val createSocketMethod = SSLSocketFactory::class.java.getDeclaredMethod(
                "createSocket",
                Socket::class.java,
                String::class.java,
                Int::class.java,
                Boolean::class.java
            )

            // 只有当这个方法不是抽象方法时才hook它
            if (!Modifier.isAbstract(createSocketMethod.modifiers)) {
                Pine.hook(createSocketMethod, object : MethodHook() {
                    override fun beforeCall(callFrame: Pine.CallFrame) {
                        val plainSocket = callFrame.args[0] as? Socket ?: return
                        val host = callFrame.args[1] as? String ?: return

                        val info = MetricsHolder.getSocketInfo(plainSocket) ?: return
                        // 记录原始Socket的SSL开始时间
                        info.secureConnectStartTime = System.nanoTime()
                        info.isSSL = true
                        info.host = host

                        // 对于HTTPS连接，更新URL scheme
                        if (info.url != null && info.url!!.startsWith("http://")) {
                            info.url = info.url!!.replace("http://", "https://")
                        }

                        Log.d(TAG, "SSL握手开始: ${plainSocket.hashCode()}, host: $host, requestId: ${info.requestId}")
                    }

                    override fun afterCall(callFrame: Pine.CallFrame) {
                        if (callFrame.throwable != null || callFrame.result == null) return

                        val plainSocket = callFrame.args[0] as? Socket ?: return
                        val sslSocket = callFrame.result as SSLSocket

                        val oldInfo = MetricsHolder.getSocketInfo(plainSocket) ?: return

                        // 将原始Socket的信息复制到SSL Socket
                        val newInfo = MetricsHolder.getOrCreateSocketInfo(sslSocket)
                        newInfo.requestId = oldInfo.requestId
                        newInfo.host = oldInfo.host
                        newInfo.port = oldInfo.port
                        newInfo.connectStartTime = oldInfo.connectStartTime
                        newInfo.dnsStartTime = oldInfo.dnsStartTime
                        newInfo.dnsEndTime = oldInfo.dnsEndTime
                        newInfo.isSSL = true
                        newInfo.url = oldInfo.url
                        newInfo.secureConnectStartTime = oldInfo.secureConnectStartTime
                        newInfo.connectEndTime = oldInfo.connectEndTime

                        Log.d(TAG, "SSL Socket创建: ${sslSocket.hashCode()}, " +
                                "从 ${plainSocket.hashCode()} 复制信息, requestId: ${newInfo.requestId}")

                        // 添加握手完成监听器
                        try {
                            sslSocket.addHandshakeCompletedListener { event ->
                                val socket = event.socket as SSLSocket
                                val info = MetricsHolder.getSocketInfo(socket)
                                if (info != null) {
                                    info.secureConnectEndTime = System.nanoTime()
                                    val sslTime = (info.secureConnectEndTime - info.secureConnectStartTime) / 1_000_000

                                    Log.d(TAG, """
                                        SSL握手完成:
                                        Socket: ${socket.hashCode()}
                                        耗时: $sslTime ms
                                        协议: ${event.session.protocol}
                                        加密套件: ${event.session.cipherSuite}
                                        requestId: ${info.requestId}
                                    """.trimIndent())
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "添加SSL握手监听器失败", e)
                        }
                    }
                })
            }

            // 尝试hook常用的SSLSocketFactory实现类
            try {
                val factoryClass = Class.forName("com.android.org.conscrypt.OpenSSLSocketFactoryImpl")
                val implMethod = factoryClass.getDeclaredMethod(
                    "createSocket",
                    Socket::class.java,
                    String::class.java,
                    Int::class.java,
                    Boolean::class.java
                )

                Pine.hook(implMethod, createSSLSocketHook())
            } catch (e: Exception) {
                // 可能找不到特定实现，忽略
            }

            // 尝试hook其他常见的SSL Socket工厂实现
            try {
                val factoryClass = Class.forName("javax.net.ssl.SSLSocketFactoryImpl")
                val implMethod = factoryClass.getDeclaredMethod(
                    "createSocket",
                    Socket::class.java,
                    String::class.java,
                    Int::class.java,
                    Boolean::class.java
                )

                Pine.hook(implMethod, createSSLSocketHook())
            } catch (e: Exception) {
                // 可能找不到特定实现，忽略
            }

        } catch (e: Exception) {
            Log.e(TAG, "Hook SSL Socket创建方法失败", e)
        }
    }

    private fun createSSLSocketHook(): MethodHook {
        return object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val plainSocket = callFrame.args[0] as? Socket ?: return
                val host = callFrame.args[1] as? String ?: return

                val info = MetricsHolder.getSocketInfo(plainSocket) ?: return
                // 记录原始Socket的SSL开始时间
                info.secureConnectStartTime = System.nanoTime()
                info.isSSL = true
                info.host = host

                Log.d(TAG, "SSL握手开始(具体实现): ${plainSocket.hashCode()}, host: $host")
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.throwable != null || callFrame.result == null) return

                val plainSocket = callFrame.args[0] as? Socket ?: return
                val sslSocket = callFrame.result as SSLSocket

                val oldInfo = MetricsHolder.getSocketInfo(plainSocket) ?: return

                // 将原始Socket的信息复制到SSL Socket
                val newInfo = MetricsHolder.getOrCreateSocketInfo(sslSocket)
                newInfo.requestId = oldInfo.requestId
                newInfo.host = oldInfo.host
                newInfo.port = oldInfo.port
                newInfo.connectStartTime = oldInfo.connectStartTime
                newInfo.dnsStartTime = oldInfo.dnsStartTime
                newInfo.dnsEndTime = oldInfo.dnsEndTime
                newInfo.isSSL = true
                newInfo.url = oldInfo.url
                newInfo.secureConnectStartTime = oldInfo.secureConnectStartTime
                newInfo.connectEndTime = oldInfo.connectEndTime

                // 添加握手完成监听器
                try {
                    sslSocket.addHandshakeCompletedListener { event ->
                        val socket = event.socket as SSLSocket
                        val info = MetricsHolder.getSocketInfo(socket)
                        if (info != null) {
                            info.secureConnectEndTime = System.nanoTime()
                            val sslTime = (info.secureConnectEndTime - info.secureConnectStartTime) / 1_000_000
                            Log.d(TAG, "SSL握手完成(具体实现): ${socket.hashCode()}, 耗时: $sslTime ms")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "添加SSL握手监听器失败", e)
                }
            }
        }
    }

    private fun reportSocketMetrics(socket: Socket, info: SocketInfo) {
        // 计算各种时间
        val totalTime = if (info.connectStartTime > 0)
            (System.nanoTime() - info.connectStartTime) / 1_000_000 else -1

        val dnsTime = if (info.dnsStartTime > 0 && info.dnsEndTime > 0)
            (info.dnsEndTime - info.dnsStartTime) / 1_000_000 else -1

        val connectTime = if (info.connectStartTime > 0 && info.connectEndTime > 0)
            (info.connectEndTime - info.connectStartTime) / 1_000_000 else -1

        val sslTime = if (info.secureConnectStartTime > 0 && info.secureConnectEndTime > 0)
            (info.secureConnectEndTime - info.secureConnectStartTime) / 1_000_000 else -1

        val ttfb = if (info.connectEndTime > 0 && info.responseHeadersEndTime > 0)
            (info.responseHeadersEndTime - info.connectEndTime) / 1_000_000 else -1

        // 构建URL (如果没有从HTTP头中解析到)
        val url = info.url ?: let {
            val scheme = if (info.isSSL) "https" else "http"
            "$scheme://${info.host ?: "unknown"}:${info.port}"
        }

        // 创建度量对象
        val metrics = NetworkMetrics(
            requestId = info.requestId,
            url = url,
            method = info.method,
            startTime = info.connectStartTime / 1_000_000, // 转为毫秒
            totalTime = totalTime,
            dnsTime = dnsTime,
            connectTime = connectTime,
            sslTime = sslTime,
            ttfb = ttfb,
            requestSize = info.totalRequestSize,
            responseSize = info.totalResponseSize,
            success = info.responseCode in 200..299,
            responseCode = if (info.responseCode > 0) info.responseCode else -1,
            errorMessage = info.errorMessage,
            networkEnv = NetworkMonitor.getNetworkEnv(),
            deviceInfo = NetworkMonitor.getDeviceInfo()
        )

        // 上报指标
        NetworkMonitor.report(metrics)

        Log.i(TAG, """
            Socket请求完成:
            URL: $url
            总耗时: $totalTime ms
            DNS耗时: $dnsTime ms
            连接耗时: $connectTime ms
            SSL耗时: $sslTime ms
            TTFB: $ttfb ms
            请求大小: ${info.totalRequestSize} bytes
            响应大小: ${info.totalResponseSize} bytes
            响应码: ${info.responseCode}
            requestId: ${info.requestId}
        """.trimIndent())
    }
}

// 线程本地存储，用于关联DNS查询和Socket连接
object ThreadLocalStorage {
    private val threadLocal = ThreadLocal<DNSLookupInfo>()

    fun getOrCreate(): DNSLookupInfo {
        var info = threadLocal.get()
        if (info == null) {
            info = DNSLookupInfo()
            threadLocal.set(info)
        }
        return info
    }

    fun clear() {
        threadLocal.remove()
    }

    class DNSLookupInfo {
        var dnsLookupStart: Long = 0
        var dnsEndTime: Long = 0
        var dnsTime: Long = 0
        var dnsHost: String? = null
    }
}