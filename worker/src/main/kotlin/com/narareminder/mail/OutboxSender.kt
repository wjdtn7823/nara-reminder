package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

// 가입 확인·비밀번호 재설정 링크는 바로 받아야 하므로 10초마다 보낸다
@Component
class OutboxSender(
    private val outboxMailRepository: OutboxMailRepository,
    private val mailNotifier: MailNotifier,
    private val discordNotifier: DiscordNotifier,
    private val mailQuota: MailQuota,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT15S")
    fun send() {
        val batch = outboxMailRepository.findTop20ByStatusOrderByIdAsc(OutboxStatus.PENDING)
        if (batch.isEmpty()) return
        val now = clock.instant()
        var sent = 0
        batch.forEach { mail ->
            try {
                mailNotifier.sendHtml(mail.toAddress, mail.subject, mail.html)
                mail.markSent(now)
                sent++
            } catch (e: Exception) {
                log.warn("[OUTBOX] {} 메일 발송 실패", mail.toAddress, e)
                if (mail.recordFailure(MAX_ATTEMPTS)) alertExhausted(mail, e)
            }
        }
        outboxMailRepository.saveAll(batch)
        if (sent > 0) mailQuota.check(now)
    }

    private fun alertExhausted(mail: OutboxMail, cause: Exception) {
        try {
            discordNotifier.send(
                "📭 **[나라장터 알림 서버] 인증 메일 ${MAX_ATTEMPTS}회 실패** ${mail.toAddress} / ${mail.subject}\n" +
                    "```\n${cause.message ?: cause.javaClass.simpleName}\n```",
            )
        } catch (e: Exception) {
            log.warn("[OUTBOX] 발송 실패 디스코드 알림 실패", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
