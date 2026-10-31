package com.narareminder.job

import com.narareminder.PostgresTestConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class JobStatePersistenceTest @Autowired constructor(
    private val jobStateRepository: JobStateRepository,
) {
    private val now = Instant.parse("2026-10-24T05:00:00Z")

    @Test
    fun `작업 상태를 저장하고 갱신한다`() {
        jobStateRepository.saveAndFlush(JobState(JobName.BID_POLLING).apply { recordFailure() })

        val state = jobStateRepository.findByIdOrNull(JobName.BID_POLLING)
        assertThat(state?.consecutiveFailures).isEqualTo(1)

        state?.recordSuccess(now)
        state?.let { jobStateRepository.saveAndFlush(it) }

        val updated = jobStateRepository.findByIdOrNull(JobName.BID_POLLING)
        assertThat(updated?.lastSuccessAt).isEqualTo(now)
        assertThat(updated?.consecutiveFailures).isZero()
    }
}
