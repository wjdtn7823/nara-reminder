package com.narareminder

import com.narareminder.job.NoticeJobs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class WorkerApplicationTest @Autowired constructor(
    private val noticeJobs: NoticeJobs,
) {
    @Test
    fun `worker 컨텍스트가 설정·DB 마이그레이션과 함께 뜬다`() {
        assertThat(noticeJobs).isNotNull()
    }
}
