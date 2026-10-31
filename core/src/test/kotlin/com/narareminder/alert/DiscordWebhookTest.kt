package com.narareminder.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DiscordWebhookTest {

    private val server = MockWebServer().apply { start() }
    private val webhook = DiscordWebhook(server.url("/api/webhooks/1/token").toString())

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun sentBody(): Map<String, Any> = jacksonObjectMapper().readValue(server.takeRequest().body.readUtf8())

    @Test
    fun `메시지를 멘션 없이 JSON 으로 보낸다`() {
        server.enqueue(MockResponse().setResponseCode(204))

        webhook.send("@everyone 오류")

        val body = sentBody()
        assertThat(body["content"]).isEqualTo("@everyone 오류")
        assertThat(body["allowed_mentions"]).isEqualTo(mapOf("parse" to emptyList<String>()))
    }

    @Test
    fun `디스코드 한도인 2000자로 자른다`() {
        server.enqueue(MockResponse().setResponseCode(204))

        webhook.send("가".repeat(2_500))

        assertThat(sentBody()["content"] as String).hasSize(2_000)
    }

    @Test
    fun `서비스키는 가려서 보낸다`() {
        server.enqueue(MockResponse().setResponseCode(204))

        webhook.send("GET https://apis.data.go.kr/x?serviceKey=abc%2B123&pageNo=1 실패")

        assertThat(sentBody()["content"] as String)
            .contains("serviceKey=***&pageNo=1")
            .doesNotContain("abc")
    }

    @Test
    fun `2xx 가 아니면 예외를 던진다`() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertThatThrownBy { webhook.send("x") }.hasMessageContaining("429")
    }
}
