package com.qihoo.net.monitor.demo;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class Test {

    public static RequestQueue requestQueue;

    public static void testHttpUrl() {
        try {
            URL url = new URL("https://suggest.taobao.com/sug?code=utf-8&q=%E5%8D%AB%E8%A1%A3&callback=cb");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            HttpURLConnection connection1 = (HttpURLConnection) new URL("jjj").openConnection();
            connection1.setConnectTimeout(0);
//            HttpURLConnection connection = MonitoredHttpURLConnection.getHttpURLConnection(url);
//            connection1.setConnectTimeout(100);
            // 设置请求方法和超时时间
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            // 获取响应码
            int responseCode = connection.getResponseCode();

            // 处理成功响应
            if (responseCode == 200) {
                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder response = new StringBuilder();
                String line;

                // 读取响应内容
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                Log.d("HttpURLTest", "响应内容: " + response);

                // 关闭资源
                reader.close();
                inputStream.close();
            }

            // 断开连接
            connection.disconnect();

        } catch (Exception e) {
            // 捕获并处理可能的异常（如网络异常、IO异常等）
            Log.e("HttpURLTest", "请求失败", e);
        }
    }

    public static void testVolley(Context context) {
        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(context.getApplicationContext());
        }
        getRequestWithVolley("https://suggest.taobao.com/sug?code=utf-8&q=%E5%8D%AB%E8%A1%A3&callback=cb");
    }

    private static void getRequestWithVolley(String url) {
        // 创建 StringRequest（返回数据为字符串）
        StringRequest stringRequest = new StringRequest(
                Request.Method.GET,  // 请求方法
                url,                 // 请求地址
                // 成功回调
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        // 主线程中处理响应（直接更新 UI）
                        Log.d("Volley", "Volley 响应内容: " + response);
                    }
                },
                // 失败回调
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d("Volley", "Volley 请求失败: ");
                    }
                }
        );

        // 将请求添加到全局队列（MyApplication 中初始化的队列）
        requestQueue.add(stringRequest);
    }
}
