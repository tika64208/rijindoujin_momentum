package com.example.momentum.data.net

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 统一轻量级 HTTP GET 请求执行器。
 *
 * 特性：
 * - 统一管理超时与 User-Agent
 * - 规范连接与输入流资源释放（use / disconnect）
 * - 支持显式指定字符编码（UTF-8, GBK 等）
 * - 统一 HTTP 错误处理
 */
object HttpExecutor {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    val GBK: Charset = Charset.forName("GBK")
    val UTF8: Charset = Charsets.UTF_8

    /**
     * 发送 HTTP GET 请求并以指定编码读取字符串响应。
     */
    fun get(
        urlStr: String,
        referer: String? = null,
        userAgent: String = DEFAULT_UA,
        charset: Charset = UTF8,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000,
        headers: Map<String, String> = emptyMap()
    ): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            referer?.let { conn.setRequestProperty("Referer", it) }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP $code for $urlStr")
            }
            return conn.inputStream.bufferedReader(charset).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
