package com.narareminder.mail

import com.narareminder.config.testProperties
import com.narareminder.job.JobName
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.aNotice
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
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

    private fun stored(
        noticeNo: String = "N1",
        noticeOrd: String = "000",
        source: NoticeSource = NoticeSource.BID,
        title: String = "AI 플랫폼 구축",
        price: Long? = 100_000_000,
        agency: String? = "조달청",
        postedAt: LocalDateTime? = LocalDateTime.of(2026, 10, 24, 13, 0),
        deadlineAt: LocalDateTime? = LocalDateTime.of(2026, 11, 3, 10, 0),
    ) = StoredNotice.of(
        aNotice(noticeNo, noticeOrd, title, source, price = price, agency = agency, postedAt = postedAt, deadlineAt = deadlineAt),
        now,
    )

    @Test
    fun `모아 보내기 메일은 받는 사람·건수·KST 시각이 제목에 들어간다`() {
        notifier.sendDigest("a@example.com", listOf(stored("N1"), stored("N2")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 새 공고 2건 (10/24 14:05)")
        val from = sent.captured.from.single() as InternetAddress
        assertThat(from.address).isEqualTo("bot@example.com")
        assertThat(from.personal).isEqualTo("나라장터 알림")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("a@example.com")
    }

    @Test
    fun `구분·정정 표시·가격·빈 값을 렌더링한다`() {
        notifier.sendDigest(
            "a@example.com",
            listOf(
                stored("N1", noticeOrd = "001", title = "AI 플랫폼", price = 150_000_000),
                stored("P1", noticeOrd = "0", source = NoticeSource.PRE_SPEC, title = "유지보수", price = null, agency = null, deadlineAt = null),
            ),
            now,
        )

        assertThat(html())
            .contains("<td>입찰공고</td>")
            .contains("<td>사전규격</td>")
            .contains("[정정] AI 플랫폼")
            .contains("150,000,000원")
            .contains("2026-11-03 10:00")
            .contains("https://www.g2b.go.kr/notice/N1")
            .contains("<td>-</td>")
            .contains("<th>추정가격/배정예산</th>")
    }

    @Test
    fun `공고명은 HTML 이스케이프되고 게시일 오름차순으로 정렬한다`() {
        notifier.sendDigest(
            "a@example.com",
            listOf(
                stored("LATE", title = "늦은 공고", postedAt = LocalDateTime.of(2026, 10, 24, 14, 0)),
                stored("EARLY", title = "A&B <script>", postedAt = LocalDateTime.of(2026, 10, 24, 13, 0)),
            ),
            now,
        )

        assertThat(html()).contains("A&amp;B &lt;script&gt;").doesNotContain("<script>")
        assertThat(html().indexOf("A&amp;B")).isLessThan(html().indexOf("늦은 공고"))
    }

    @Test
    fun `0건이면 발송하지 않는다`() {
        notifier.sendDigest("a@example.com", emptyList(), now)

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }

    @Test
    fun `인증 메일은 받은 HTML 을 그대로 보낸다`() {
        notifier.sendHtml("a@example.com", "[나라장터 알리미] 이메일 확인", "<p>링크</p>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알리미] 이메일 확인")
        assertThat(html()).isEqualTo("<p>링크</p>")
    }

    @Test
    fun `에러 알림 메일은 관리자들에게 작업명·연속 실패 횟수·오류를 보낸다`() {
        notifier.sendAlert(listOf("admin@example.com"), JobName.BID_POLLING, 6, Instant.parse("2026-10-24T04:30:00Z"), "용역 조회 실패: <키 오류>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 입찰공고 수집 실패 (6회 연속)")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("admin@example.com")
        assertThat(html()).contains("2026-10-24 13:30").contains("용역 조회 실패: &lt;키 오류&gt;")
    }

    @Test
    fun `관리자가 없으면 에러 알림 메일을 보내지 않는다`() {
        notifier.sendAlert(emptyList(), JobName.PRE_SPEC_DAILY, 1, null, "오류")

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }
}
