package com.narareminder.job

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class JobRunCleanup(
    private val repository: JobRunRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    fun purge(): Int {
        val deleted = repository.deleteByStartedAtBefore(clock.instant().minus(RETENTION))
        log.info("실행 이력 {}건 정리 (보관 {}일)", deleted, RETENTION.toDays())
        return deleted
    }

    companion object {
        private val RETENTION: Duration = Duration.ofDays(90)
    }
}
