package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.support.KST
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

// Gmail 은 하루 약 500명까지만 보낼 수 있어 한도 전에 관리자에게 알린다
@Component
class MailQuota(
    private val jobRunRepository: JobRunRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val discordNotifier: DiscordNotifier,
) {
    private var warnedOn: LocalDate? = null

    @Synchronized
    fun check(now: Instant) {
        val today = now.atZone(KST).toLocalDate()
        if (warnedOn == today) return
        val since = today.atStartOfDay(KST).toInstant()
        val sent = jobRunRepository.sumSentCountSince(JobName.DELIVERY, since) +
            outboxMailRepository.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, since)
        if (sent <= WARN_THRESHOLD) return
        if (discordNotifier.send("⚠️ **[나라장터 알림 서버] 오늘 메일 ${sent}통 발송** — Gmail 하루 한도(약 500통)에 가깝습니다")) {
            warnedOn = today
        }
    }

    companion object {
        private const val WARN_THRESHOLD = 400
    }
}
