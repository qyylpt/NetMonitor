package com.qihoo.net.monitor.demo;

import android.util.Log;

import com.qihoo.net.monitor.httpurl.MonitoredHttpURLConnection;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

public class Test {
    public static void testHttpUrl() {
        try {
            URL url = new URL("https://suggest.taobao.com/sug?code=utf-8&q=%E5%8D%AB%E8%A1%A3&callback=cb");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
//            HttpURLConnection connection = new MonitoredHttpURLConnection(url);

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
}
