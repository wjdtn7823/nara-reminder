package com.narareminder.mail

import com.narareminder.config.testProperties
import com.narareminder.job.JobName
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSender
import java.time.Instant
import java.time.LocalDateTime
import java.util.Properties

class MailNotifierTest {

    private val sent = slot<MimeMessage>()
    private val mailSender = mockk<JavaMailSender> {
        every { createMimeMessage() } answers { MimeMessage(Session.getInstance(Properties())) }
        every { send(capture(sent)) } just Runs
    }
    private val notifier = MailNotifier(mailSender, testProperties())

    // KST 2026-10-24 14:05
    private val now = Instant.parse("2026-10-24T05:05:00Z")

    private fun html() = sent.captured.content as String

    @Test
    fun `입찰공고 메일 제목에 건수와 KST 시각이 들어가고 수신자가 설정된다`() {
        notifier.sendNotices(NoticeSource.BID, listOf(aNotice(noticeNo = "N1"), aNotice(noticeNo = "N2")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 새 입찰공고 2건 (10/24 14:05)")
        assertThat(sent.captured.from.single().toString()).isEqualTo("bot@example.com")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("me@example.com")
    }

    @Test
    fun `사전규격 메일 제목은 날짜만 표시한다`() {
        notifier.sendNotices(NoticeSource.PRE_SPEC, listOf(aNotice(source = NoticeSource.PRE_SPEC, noticeOrd = "0")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 사전규격 1건 (10/24)")
    }

    @Test
    fun `정정공고 표시와 가격 형식과 빈 값을 렌더링한다`() {
        notifier.sendNotices(
            NoticeSource.BID,
            listOf(
                aNotice(noticeNo = "N1", noticeOrd = "001", title = "AI 플랫폼", price = 150_000_000),
                aNotice(noticeNo = "N2", title = "유지보수", price = null, agency = null, deadlineAt = null),
            ),
            now,
        )

        assertThat(html())
            .contains("[정정] AI 플랫폼")
            .contains("150,000,000원")
            .contains("2026-11-03 10:00")
            .contains("https://www.g2b.go.kr/notice/N1")
            .contains("<td>-</td>")
            .contains("<th>추정가격</th>")
    }

    @Test
    fun `사전규격 메일의 가격 열은 배정예산으로 표시한다`() {
        notifier.sendNotices(NoticeSource.PRE_SPEC, listOf(aNotice(source = NoticeSource.PRE_SPEC, noticeOrd = "0")), now)

        assertThat(html())
            .contains("<th>배정예산</th>")
            .doesNotContain("추정가격")
    }

    @Test
    fun `공고명은 HTML 이스케이프된다`() {
        notifier.sendNotices(NoticeSource.BID, listOf(aNotice(title = "A&B <script>alert(1)</script>")), now)

        assertThat(html())
            .contains("A&amp;B &lt;script&gt;")
            .doesNotContain("<script>")
    }

    @Test
    fun `게시일 오름차순으로 정렬한다`() {
        notifier.sendNotices(
            NoticeSource.BID,
            listOf(
                aNotice(noticeNo = "LATE", title = "늦은 공고", postedAt = LocalDateTime.of(2026, 10, 24, 14, 0)),
                aNotice(noticeNo = "EARLY", title = "이른 공고", postedAt = LocalDateTime.of(2026, 10, 24, 13, 0)),
            ),
            now,
        )

        assertThat(html().indexOf("이른 공고")).isLessThan(html().indexOf("늦은 공고"))
    }

    @Test
    fun `0건이면 발송하지 않는다`() {
        notifier.sendNotices(NoticeSource.BID, emptyList(), now)

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }

    @Test
    fun `에러 알림 메일에 작업명과 연속 실패 횟수와 오류가 들어간다`() {
        notifier.sendAlert(JobName.BID_POLLING, 6, Instant.parse("2026-10-24T04:30:00Z"), "용역 조회 실패: 결과코드 30 <키 오류>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 입찰공고 수집 실패 (6회 연속)")
        assertThat(html())
            .contains("2026-10-24 13:30")
            .contains("결과코드 30 &lt;키 오류&gt;")
    }

    @Test
    fun `마지막 성공 시각이 없으면 없음으로 표시한다`() {
        notifier.sendAlert(JobName.PRE_SPEC_DAILY, 1, null, "오류")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 사전규격 수집 실패 (1회 연속)")
        assertThat(html()).contains("없음")
    }
}
