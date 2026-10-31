package com.narareminder.alert

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// ERROR 로그를 디스코드로 보낸다. 같은 위치(로거 + 메시지 형식)의 에러는 30분에 한 번만 보낸다
class DiscordErrorAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {

    var webhookUrl: String = ""
    internal var sender: ((String) -> Unit)? = null
    internal var clock: Clock = Clock.systemUTC()

    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    override fun start() {
        if (sender == null && webhookUrl.isNotBlank()) sender = DiscordWebhook(webhookUrl)::send
        super.start()
    }

    override fun append(event: ILoggingEvent) {
        val send = sender ?: return
        if (!event.level.isGreaterOrEqual(Level.ERROR)) return
        if (event.markerList.orEmpty().any { it.contains(AlertMarkers.HANDLED_BY_JOB_ALERT) }) return
        if (!shouldSend("${event.loggerName}|${event.message}")) return
        try {
            send(format(event))
        } catch (e: Exception) {
            addWarn("디스코드 에러 알림 전송 실패", e)
        }
    }

    private fun shouldSend(key: String): Boolean {
        val now = clock.instant()
        var allowed = false
        lastSentAt.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous, now) >= THROTTLE) {
                allowed = true
                now
            } else {
                previous
            }
        }
        return allowed
    }

    private fun format(event: ILoggingEvent): String {
        val cause = event.throwableProxy?.let { "\n${it.className}: ${it.message.orEmpty()}" }.orEmpty()
        return "🚨 **[나라장터 알림 서버] ERROR** `${event.loggerName.substringAfterLast('.')}`\n" +
            event.formattedMessage + cause
    }

    companion object {
        private val THROTTLE: Duration = Duration.ofMinutes(30)
    }
}
