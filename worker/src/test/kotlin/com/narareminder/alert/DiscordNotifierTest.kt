package com.narareminder.alert

import com.narareminder.config.NaraProperties
import com.narareminder.config.testProperties
import com.narareminder.job.JobName
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DiscordNotifierTest {

    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun notifier(url: String) =
        DiscordNotifier(testProperties().copy(discord = NaraProperties.Discord(webhookUrl = url)))

    @Test
    fun `웹훅이 없으면 보내지 않고 false 를 돌려준다`() {
        assertThat(notifier("").sendAlert(JobName.BID_POLLING, 6, null, "오류")).isFalse()
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `배치 장애를 작업명과 연속 실패 횟수와 함께 보낸다`() {
        server.enqueue(MockResponse().setResponseCode(204))

        val sent = notifier(server.url("/hook").toString())
            .sendAlert(JobName.BID_POLLING, 6, Instant.parse("2026-10-25T04:30:00Z"), "용역 조회 실패: HTTP 500")

        assertThat(sent).isTrue()
        assertThat(server.takeRequest().body.readUtf8())
            .contains("입찰공고 수집 실패 (6회 연속)")
            .contains("2026-10-25 13:30")
            .contains("용역 조회 실패: HTTP 500")
    }
}
