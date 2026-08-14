package com.example.fbscanner

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TelegramSender {
    fun send(token: String, chatId: String, message: String): JSONObject? {
        return try {
            val urlString = "https://telegram.org"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
                           "&text=" + URLEncoder.encode(message, "UTF-8")

            val wr = OutputStreamWriter(conn.outputStream)
            wr.write(postData)
            wr.flush()

            val `in` = BufferedReader(InputStreamReader(conn.inputStream))
            val response = StringBuilder()
            var inputLine: String?
            while (`in`.readLine().also { inputLine = it } != null) {
                response.append(inputLine)
            }
            `in`.close()
            JSONObject(response.toString())
        } catch (e: Exception) {
            val errorJson = JSONObject()
            errorJson.put("ok", false)
            errorJson.put("description", e.message)
            errorJson
        }
    }
}
