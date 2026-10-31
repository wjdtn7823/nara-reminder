package com.narareminder.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Spring 컨텍스트 밖(logback appender)에서도 쓰므로 JDK HttpClient 만 사용한다
class DiscordWebhook(private val url: String) {

    private val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()

    fun send(content: String) {
        val body = mapper.writeValueAsString(
            mapOf(
                "content" to mask(content).take(MAX_LENGTH),
                // 오류 메시지에 섞인 @everyone 등이 멘션으로 동작하지 않게 한다
                "allowed_mentions" to mapOf("parse" to emptyList<String>()),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299) { "디스코드 웹훅 응답 ${response.statusCode()}" }
    }

    companion object {
        private const val MAX_LENGTH = 2000
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
        private val mapper = jacksonObjectMapper()
        private val SERVICE_KEY = Regex("serviceKey=[^&\\s\"]+")

        fun mask(text: String): String = text.replace(SERVICE_KEY, "serviceKey=***")
    }
}
