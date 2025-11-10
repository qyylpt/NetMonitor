package com.qihoo.net.monitor.demo

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.qihoo.net.monitor.NetworkMonitor
import com.qihoo.net.monitor.httpurl.MonitoredHttpURLConnection
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URL

class MainActivity : FragmentActivity() {
    private var client: OkHttpClient? = null
    private var client1: OkHttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NetworkMonitor.init(application)
        client = OkHttpClient.Builder().build()
        client1 = OkHttpClient().newBuilder().build()
        findViewById<View>(R.id.monitor_request).setOnClickListener {
            simulateOkhttp()
            simulateHttpUrl()

        }


    }

    private fun simulateHttpUrl() {
        Thread {
            try {
                val url = URL("https://suggest.taobao.com/sug?code=utf-8&q=%E5%8D%AB%E8%A1%A3&callback=cb")
                val connection = MonitoredHttpURLConnection(url)
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val inputStream = connection.inputStream
                    val response = inputStream.bufferedReader().readText()
                    Log.d("HttpURLTest", "响应内容: $response")
                    inputStream.close()
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("HttpURLTest", "请求失败", e)
            }
        }.start()
    }

    private fun simulateOkhttp() {
        client?.newCall(
            Request.Builder()
                .url("https://suggest.taobao.com/sug?code=utf-8&q=%E5%8D%AB%E8%A1%A3&callback=cb")
                .build()
        )?.enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.d("zsf_test", "onFailure")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                try {
                    // 方式1：需要响应内容时，读取完整响应体（string()/bytes() 会自动关闭流）
                    val responseBody = response.body()?.string() ?: "响应体为空"
                    Log.d("zsf_test", "响应内容: $responseBody")

                    // 方式2：不需要响应内容时，显式关闭响应体
                    // response.body()?.close()
                } catch (e: Exception) {
                    Log.e("zsf_test", "处理响应体异常", e)
                } finally {
                    // 双重保障：确保响应体已关闭（防止遗漏）
                    response.body()?.close()
                }
            }

        })
    }
}