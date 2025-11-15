package com.qihoo.net.monitor.plugin

import org.gradle.api.Project
import java.util.Date

class LogUtils {
    // 日志文件名（区分 HTTP 和 OkHttp，避免重复）
    private static final String LOG_HTTP_FILENAME = "net_http_log.txt"
    private static final String LOG_OKHTTP_FILENAME = "net_okhttp_log.txt"

    // 缓存文件对象（非空校验）
    private static File logHttpFile
    private static File logOkHttpFile
    // 时间格式（统一日志格式）
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"

    /**
     * 初始化日志文件（必须在使用 log 方法前调用）
     * @param project Gradle 项目对象，用于获取 build 目录
     */
    static synchronized void init(Project project) {
        try {
            if ((logHttpFile != null && logHttpFile.exists()) && (logOkHttpFile != null && logOkHttpFile.exists())) {
                if (logHttpFile != null) {
                    logHttpFile.append("\n \n")
                }
                if (logOkHttpFile != null) {
                    logOkHttpFile.append("\n \n")
                }
                return
            }
            def buildDir = project.buildDir.absoluteFile
            def logDir = new File(buildDir, "tmp/net")
            if (!logDir.exists()) {
                boolean dirCreated = logDir.mkdirs()
                if (!dirCreated) {
                    throw new RuntimeException("日志目录创建失败：${logDir.absolutePath}（可能没有权限）")
                }
            }
            if (logHttpFile == null) {
                logHttpFile = new File(logDir, LOG_HTTP_FILENAME)
                logHttpFile.append("[${getTimestamp()}] HTTP 开始替换\n")
            }
            if (logOkHttpFile == null) {
                logOkHttpFile = new File(logDir, LOG_OKHTTP_FILENAME)
                logOkHttpFile.append("[${getTimestamp()}] OkHttp 开始替换\n")
            }
        } catch (Exception e) {
            throw new RuntimeException("LogUtils 初始化失败：${e.message}", e)
        }
    }

    /**
     * 写入 HTTP 日志
     * @param message 日志内容
     */
    static synchronized void logHttp(String message) {
        checkInitialized() // 校验初始化状态
        try {
            // 追加带时间戳的日志
            logHttpFile.append("[${getTimestamp()}] ${message}\n", "UTF-8")
        } catch (Exception e) {
            println("HTTP 日志写入失败：${e.message}")
        }
    }

    /**
     * 写入 OkHttp 日志
     * @param message 日志内容
     */
    static synchronized void logOkhttp(String message) {
        checkInitialized() // 校验初始化状态
        try {
            // 追加带时间戳的日志
            logOkHttpFile.append("[${getTimestamp()}] ${message}\n", "UTF-8")
        } catch (Exception e) {
            println("OkHttp 日志写入失败：${e.message}")
        }
    }

    /**
     * 校验是否已初始化，未初始化则抛异常
     */
    private static void checkInitialized() {
        if (logHttpFile == null || logOkHttpFile == null) {
            throw new IllegalStateException("LogUtils 未初始化，请先调用 init(Project) 方法")
        }
    }

    /**
     * 获取当前时间戳（格式化）
     */
    private static String getTimestamp() {
        return new Date().format(DATE_FORMAT)
    }
}