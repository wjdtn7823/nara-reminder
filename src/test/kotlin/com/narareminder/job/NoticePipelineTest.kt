package com.narareminder.job

import com.narareminder.client.NaraApiClient
import com.narareminder.client.NaraApiException
import com.narareminder.config.NaraProperties
import com.narareminder.config.testProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import com.narareminder.store.NotifiedNotice
import com.narareminder.store.NotifiedNoticeRepository
import com.narareminder.store.notifiedId
import com.narareminder.support.KST
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
import java.time.LocalDateTime
import java.util.Optional

class NoticePipelineTest {

    // KST 2026-10-24 14:00
    private val now = Instant.parse("2026-10-24T05:00:00Z")
    private val client = mockk<NaraApiClient>()
    private val notifiedNoticeRepository = mockk<NotifiedNoticeRepository>()
    private val jobStateRepository = mockk<JobStateRepository>()
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val state = JobState(JobName.BID_POLLING)

    private val aiFilter = NaraProperties.Filter(keywords = listOf("AI"), categories = listOf(NoticeCategory.SERVICE))

    private fun pipeline(props: NaraProperties = testProperties(filter = aiFilter)) =
        NoticePipeline(client, notifiedNoticeRepository, jobStateRepository, mailNotifier, props, Clock.fixed(now, KST))

    private fun runBid(props: NaraProperties = testProperties(filter = aiFilter)) =
        runBlocking { pipeline(props).run(JobName.BID_POLLING) }

    private fun sentNoticeNos(): List<String> {
        val captured = mutableListOf<List<Notice>>()
        verify { mailNotifier.sendNotices(NoticeSource.BID, capture(captured), now) }
        return captured.last().map { it.noticeNo }
    }

    @BeforeEach
    fun setUp() {
        every { jobStateRepository.findById(JobName.BID_POLLING) } returns Optional.of(state)
        every { jobStateRepository.save(any<JobState>()) } answers { firstArg() }
        every { notifiedNoticeRepository.findAllById(any()) } returns emptyList()
        every { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) } answers { firstArg() }
    }

    @Test
    fun `최초 실행은 initialLookback 구간을 KST로 조회한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()

        runBid()

        coVerify {
            client.fetch(
                NoticeSource.BID,
                NoticeCategory.SERVICE,
                LocalDateTime.of(2026, 10, 24, 13, 0),
                LocalDateTime.of(2026, 10, 24, 14, 0),
            )
        }
    }

    @Test
    fun `조건에 맞는 새 공고만 메일로 보내고 기록한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(
            aNotice(noticeNo = "N1", title = "AI 플랫폼"),
            aNotice(noticeNo = "N2", title = "청사 청소"),
        )

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N1")
        verify { notifiedNoticeRepository.saveAll(match<List<NotifiedNotice>> { it.single().id.noticeNo == "N1" }) }
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `맞는 공고가 없으면 메일 없이 성공으로 기록한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(aNotice(title = "청사 청소"))

        runBid()

        verify(exactly = 0) { mailNotifier.sendNotices(any(), any(), any()) }
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `이미 보낸 공고는 제외한다`() {
        val sent = aNotice(noticeNo = "N1", title = "AI 플랫폼")
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(sent, aNotice(noticeNo = "N3", title = "AI 분석"))
        every { notifiedNoticeRepository.findAllById(any()) } returns listOf(NotifiedNotice(sent.notifiedId(), sent.title, now))

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N3")
    }

    @Test
    fun `같은 실행에서 중복 조회된 공고는 한 번만 보낸다`() {
        val notice = aNotice(noticeNo = "N1", title = "AI 플랫폼")
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(notice, notice.copy())

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N1")
    }

    @Test
    fun `메일 발송이 실패하면 기록하지 않고 실패로 처리한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(aNotice(title = "AI 플랫폼"))
        every { mailNotifier.sendNotices(any(), any(), any()) } throws RuntimeException("SMTP 오류")

        runBid()

        verify(exactly = 4) { mailNotifier.sendNotices(any(), any(), any()) }
        verify(exactly = 0) { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) }
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `일부 업무구분만 실패하면 성공분은 보내고 실행은 실패로 처리한다`() {
        val props = testProperties(
            filter = aiFilter.copy(categories = listOf(NoticeCategory.SERVICE, NoticeCategory.GOODS)),
        )
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns
            listOf(aNotice(noticeNo = "N1", title = "AI 플랫폼"))
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.GOODS, any(), any()) } throws
            NaraApiException("HTTP 500", retryable = true)

        runBid(props)

        assertThat(sentNoticeNos()).containsExactly("N1")
        verify { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) }
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `연속 실패가 임계치에 도달하면 에러 알림을 한 번만 보낸다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } throws NaraApiException("결과코드 30: 키 오류", retryable = false)
        val props = testProperties(filter = aiFilter, bidAlertAfterFailures = 2)

        runBid(props)
        verify(exactly = 0) { mailNotifier.sendAlert(any(), any(), any(), any()) }

        runBid(props)
        runBid(props)

        verify(exactly = 1) {
            mailNotifier.sendAlert(JobName.BID_POLLING, 2, null, match { it.contains("결과코드 30") })
        }
        assertThat(state.alertSent).isTrue()
        assertThat(state.consecutiveFailures).isEqualTo(3)
    }

    @Test
    fun `에러 알림 발송이 실패하면 다음 실패 때 다시 시도한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } throws NaraApiException("HTTP 500", retryable = true)
        every { mailNotifier.sendAlert(any(), any(), any(), any()) } throws RuntimeException("SMTP 오류")
        val props = testProperties(filter = aiFilter, bidAlertAfterFailures = 1)

        runBid(props)

        assertThat(state.alertSent).isFalse()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `성공하면 실패 횟수와 알림 상태를 초기화한다`() {
        state.consecutiveFailures = 7
        state.alertSent = true
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()

        runBid()

        assertThat(state.consecutiveFailures).isZero()
        assertThat(state.alertSent).isFalse()
        verify { jobStateRepository.save(state) }
    }
}
