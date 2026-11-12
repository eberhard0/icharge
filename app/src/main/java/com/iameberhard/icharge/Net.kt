package com.iameberhard.icharge

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection: two requests a day do not need a client library. */
object Net {
    private const val UA = "icharge/${BuildConfig.VERSION_NAME} (personal EV charger app)"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String = request(url, null, headers)

    fun post(url: String, form: String): String =
        request(url, form, mapOf("Content-Type" to "application/x-www-form-urlencoded"))

    fun postJson(url: String, body: String, headers: Map<String, String>): String =
        request(url, body, headers + ("Content-Type" to "application/json"))

    private fun request(url: String, body: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30_000
            c.readTimeout = 150_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
