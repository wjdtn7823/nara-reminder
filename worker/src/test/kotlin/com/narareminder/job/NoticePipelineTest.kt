package com.narareminder.job

import com.narareminder.alert.DiscordNotifier
import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.config.testProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.Optional

class NoticePipelineTest {

    // KST 2026-10-24 14:00
    private val now = Instant.parse("2026-10-24T05:00:00Z")
    private val client = mockk<NaraApiClient>()
    private val collector = mockk<NoticeCollector>()
    private val jobStateRepository = mockk<JobStateRepository>()
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val userRepository = mockk<AppUserRepository> { every { findAdmins() } returns listOf(AppUser("admin@example.com", "hash", true, Instant.EPOCH)) }
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val jobRunRepository = mockk<JobRunRepository>()
    private val state = JobState(JobName.BID_POLLING)
    private val runs = mutableListOf<JobRun>()

    private fun runBid(props: NaraProperties = testProperties()) = runBlocking {
        NoticePipeline(client, collector, jobStateRepository, mailNotifier, userRepository, discordNotifier, jobRunRepository, props, Clock.fixed(now, KST))
            .run(JobName.BID_POLLING)
    }

    @BeforeEach
    fun setUp() {
        every { jobRunRepository.save(capture(runs)) } answers { firstArg() }
        every { jobStateRepository.findById(JobName.BID_POLLING) } returns Optional.of(state)
        every { jobStateRepository.save(any<JobState>()) } answers { firstArg() }
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()
        every { collector.storeAndMatch(any(), now) } returns NoticeCollector.Result(0, 0)
    }

    @Test
    fun `모든 업무구분을 조회해 저장·매칭에 넘기고 건수와 함께 이력을 남긴다`() {
        val notice = aNotice(noticeNo = "N1")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns listOf(notice)
        every { collector.storeAndMatch(listOf(notice), now) } returns NoticeCollector.Result(1, 2)

        runBid()

        coVerify(exactly = 4) { client.fetch(NoticeSource.BID, any(), any(), any()) }
        val run = runs.single()
        assertThat(run.status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(listOf(run.fetchedCount, run.matchedCount, run.sentCount)).containsExactly(1, 1, 2)
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `한 업무구분 조회가 실패하면 나머지는 저장하고 실패로 남긴다`() {
        val notice = aNotice(noticeNo = "N1")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.GOODS, any(), any()) } throws IllegalStateException("HTTP 500")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns listOf(notice)
        every { collector.storeAndMatch(listOf(notice), now) } returns NoticeCollector.Result(1, 1)

        runBid()

        verify { collector.storeAndMatch(listOf(notice), now) }
        assertThat(runs.single().status).isEqualTo(JobRunStatus.FAILURE)
        assertThat(runs.single().error).contains("물품 조회 실패: HTTP 500")
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `저장·매칭이 실패하면 구간을 넘기지 않는다`() {
        every { collector.storeAndMatch(any(), now) } throws IllegalStateException("DB 오류")

        runBid()

        assertThat(runs.single().error).contains("공고 저장/매칭 실패: DB 오류")
        assertThat(state.lastSuccessAt).isNull()
    }

    @Test
    fun `연속 실패가 임계치에 닿으면 메일과 디스코드로 한 번 알린다`() {
        every { collector.storeAndMatch(any(), now) } throws IllegalStateException("DB 오류")

        runBid(testProperties(bidAlertAfterFailures = 1))

        verify(exactly = 1) { mailNotifier.sendAlert(listOf("admin@example.com"), JobName.BID_POLLING, 1, null, any()) }
        verify(exactly = 1) { discordNotifier.sendAlert(JobName.BID_POLLING, 1, null, any()) }
        assertThat(state.alertSent).isTrue()
    }
}
