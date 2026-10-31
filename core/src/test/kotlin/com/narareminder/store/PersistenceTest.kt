package com.narareminder.store

import com.narareminder.PostgresTestConfig
import com.narareminder.job.JobName
import com.narareminder.job.JobState
import com.narareminder.job.JobStateRepository
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
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
class PersistenceTest @Autowired constructor(
    private val notifiedNoticeRepository: NotifiedNoticeRepository,
    private val jobStateRepository: JobStateRepository,
) {
    private val now = Instant.parse("2026-10-24T05:00:00Z")

    @Test
    fun `발송 이력을 공고 키로 조회한다`() {
        val sent = aNotice(noticeNo = "N1")
        notifiedNoticeRepository.saveAndFlush(NotifiedNotice(sent.notifiedId(), sent.title, now))

        val found = notifiedNoticeRepository.findAllById(
            listOf(sent.notifiedId(), aNotice(noticeNo = "N2").notifiedId()),
        )

        assertThat(found.map { it.id }).containsExactly(sent.notifiedId())
    }

    @Test
    fun `같은 공고의 다른 차수와 다른 출처는 별도로 저장된다`() {
        val original = aNotice(noticeNo = "N1", noticeOrd = "000")
        val revision = aNotice(noticeNo = "N1", noticeOrd = "001")
        val preSpec = aNotice(noticeNo = "N1", noticeOrd = "0", source = NoticeSource.PRE_SPEC)

        notifiedNoticeRepository.saveAllAndFlush(
            listOf(original, revision, preSpec).map { NotifiedNotice(it.notifiedId(), it.title, now) },
        )

        assertThat(notifiedNoticeRepository.count()).isEqualTo(3)
    }

    @Test
    fun `긴 공고명도 저장된다`() {
        val notice = aNotice(title = "가".repeat(2_000))

        notifiedNoticeRepository.saveAndFlush(NotifiedNotice(notice.notifiedId(), notice.title, now))

        assertThat(notifiedNoticeRepository.findByIdOrNull(notice.notifiedId())?.title).hasSize(2_000)
    }

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
