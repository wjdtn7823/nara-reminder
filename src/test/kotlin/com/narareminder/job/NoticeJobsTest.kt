package com.narareminder.job

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class NoticeJobsTest {

    private val pipeline = mockk<NoticePipeline>().also { mock ->
        coEvery { mock.run(any()) } returns Unit
    }
    private val jobs = NoticeJobs(pipeline)

    @Test
    fun `입찰공고 폴링은 BID_POLLING 작업을 실행한다`() = runBlocking<Unit> {
        jobs.pollBids()

        coVerify { pipeline.run(JobName.BID_POLLING) }
    }

    @Test
    fun `사전규격 수집은 PRE_SPEC_DAILY 작업을 실행한다`() = runBlocking<Unit> {
        jobs.collectPreSpecs()

        coVerify { pipeline.run(JobName.PRE_SPEC_DAILY) }
    }

    @Test
    fun `입찰공고는 운영 설정대로 매일 아침 8시(KST)에 한 번 수집한다`() {
        val environment = StandardEnvironment().apply {
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                .forEach { propertySources.addFirst(it) }
        }
        val scheduled = NoticeJobs::class.java.methods.first { it.name == "pollBids" }.getAnnotation(Scheduled::class.java)
        val cron = CronExpression.parse(environment.resolveRequiredPlaceholders(scheduled.cron))
        val zone = ZoneId.of(scheduled.zone)

        val after = ZonedDateTime.of(2026, 11, 8, 8, 30, 0, 0, KST)
        val first = requireNotNull(cron.next(after.withZoneSameInstant(zone)))
        val second = requireNotNull(cron.next(first))

        assertThat(first.toInstant()).isEqualTo(ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, KST).toInstant())
        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofDays(1))
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
