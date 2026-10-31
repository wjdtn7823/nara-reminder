package com.narareminder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class ApiApplicationTest @Autowired constructor(
    private val context: ApplicationContext,
) {
    @Test
    fun `api 컨텍스트에는 수집 스케줄러가 없다`() {
        assertThat(context.containsBean("noticeJobs")).isFalse()
    }
}
