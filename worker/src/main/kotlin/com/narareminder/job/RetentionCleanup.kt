package com.narareminder.job

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.mail.OutboxStatus
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.user.EmailTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class RetentionCleanup(
    private val noticeRepository: StoredNoticeRepository,
    private val jobRunRepository: JobRunRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val notices: Int, val runs: Int, val tokens: Int, val outbox: Long)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    fun purge(): Result {
        val now = clock.instant()
        val cutoff = now.minus(RETENTION)
        val result = Result(
            notices = noticeRepository.deleteCollectedBefore(cutoff),
            runs = jobRunRepository.deleteByStartedAtBefore(cutoff),
            tokens = emailTokenRepository.deleteUsedOrExpired(now),
            outbox = outboxMailRepository.deleteByStatusNotAndCreatedAtBefore(OutboxStatus.PENDING, now.minus(OUTBOX_RETENTION)).toLong(),
        )
        log.info("보관 기간 정리: {}", result)
        return result
    }

    companion object {
        private val RETENTION: Duration = Duration.ofDays(90)
        private val OUTBOX_RETENTION: Duration = Duration.ofDays(7)
    }
}
