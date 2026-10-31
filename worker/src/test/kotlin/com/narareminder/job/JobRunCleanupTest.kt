package com.narareminder.job

import com.narareminder.PostgresTestConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class JobRunCleanupTest @Autowired constructor(private val repository: JobRunRepository) {

    private val now = Instant.parse("2026-10-25T00:00:00Z")

    private fun runAt(startedAt: Instant) = JobRun(
        jobName = JobName.BID_POLLING, startedAt = startedAt, finishedAt = startedAt,
        windowFrom = startedAt, windowTo = startedAt, fetchedCount = 0, matchedCount = 0, sentCount = 0,
        status = JobRunStatus.SUCCESS, error = null,
    )

    @Test
    fun `90일이 지난 이력만 지운다`() {
        repository.saveAll(listOf(runAt(now.minus(Duration.ofDays(91))), runAt(now.minus(Duration.ofDays(89)))))

        val deleted = JobRunCleanup(repository, Clock.fixed(now, ZoneOffset.UTC)).purge()

        assertThat(deleted).isEqualTo(1)
        assertThat(repository.findAll().map { it.startedAt }).containsExactly(now.minus(Duration.ofDays(89)))
    }
}
