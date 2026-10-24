package com.ssmediaos.smsgateway

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    data class Reply(val status: Int, val body: String) {
        fun json(): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
    }

    fun post(baseUrl: String, path: String, token: String, json: JSONObject? = null): Reply {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("X-Gateway-Token", token)
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText() } ?: ""
            return Reply(status, body)
        } finally { connection.disconnect() }
    }
}
