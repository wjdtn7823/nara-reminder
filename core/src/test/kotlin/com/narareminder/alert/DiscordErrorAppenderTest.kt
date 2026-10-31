package com.narareminder.alert

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class DiscordErrorAppenderTest {

    private val sent = mutableListOf<String>()
    private var now = Instant.parse("2026-10-25T00:00:00Z")
    private val context = LoggerContext()

    private val appender = DiscordErrorAppender().apply {
        context = this@DiscordErrorAppenderTest.context
        sender = { sent += it }
        clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        start()
    }

    private fun event(
        message: String,
        vararg args: Any,
        level: Level = Level.ERROR,
        logger: String = "com.narareminder.job.NoticePipeline",
        throwable: Throwable? = null,
    ) = LoggingEvent("fqcn", context.getLogger(logger), level, message, throwable, args)

    @Test
    fun `ERROR 로그를 메시지와 예외 요약으로 보낸다`() {
        appender.doAppend(event("[{}] 실행 이력 저장 실패", "BID_POLLING", throwable = IllegalStateException("DB 연결 끊김")))

        assertThat(sent.single())
            .contains("NoticePipeline")
            .contains("[BID_POLLING] 실행 이력 저장 실패")
            .contains("IllegalStateException: DB 연결 끊김")
    }

    @Test
    fun `ERROR 미만은 보내지 않는다`() {
        appender.doAppend(event("경고", level = Level.WARN))

        assertThat(sent).isEmpty()
    }

    @Test
    fun `같은 위치의 에러는 30분에 한 번만 보낸다`() {
        appender.doAppend(event("[{}] 저장 실패 {}회", "BID", 1))
        now = now.plus(Duration.ofMinutes(29))
        appender.doAppend(event("[{}] 저장 실패 {}회", "BID", 2))
        now = now.plus(Duration.ofMinutes(2))
        appender.doAppend(event("[{}] 저장 실패 {}회", "BID", 3))

        assertThat(sent).hasSize(2)
        assertThat(sent.last()).contains("저장 실패 3회")
    }

    @Test
    fun `다른 위치의 에러는 따로 보낸다`() {
        appender.doAppend(event("A 실패"))
        appender.doAppend(event("B 실패"))

        assertThat(sent).hasSize(2)
    }

    @Test
    fun `배치 장애 알림이 처리하는 로그는 보내지 않는다`() {
        appender.doAppend(event("수집 실패").apply { addMarker(AlertMarkers.HANDLED_BY_JOB_ALERT) })

        assertThat(sent).isEmpty()
    }

    @Test
    fun `전송이 실패해도 예외를 밖으로 던지지 않는다`() {
        appender.sender = { error("디스코드 다운") }

        appender.doAppend(event("오류"))

        assertThat(sent).isEmpty()
    }

    @Test
    fun `웹훅이 없으면 아무것도 하지 않는다`() {
        val disabled = DiscordErrorAppender().apply {
            context = this@DiscordErrorAppenderTest.context
            webhookUrl = ""
            start()
        }

        disabled.doAppend(event("오류"))

        assertThat(sent).isEmpty()
    }
}
