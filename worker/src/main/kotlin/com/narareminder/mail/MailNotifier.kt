package com.narareminder.mail

import com.narareminder.config.NaraProperties
import com.narareminder.job.JobName
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeSource
import com.narareminder.support.KST
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class MailNotifier(
    private val mailSender: JavaMailSender,
    private val props: NaraProperties,
) {
    fun sendNotices(source: NoticeSource, notices: List<Notice>, now: Instant) {
        if (notices.isEmpty()) return
        val subject = when (source) {
            NoticeSource.BID -> "[나라장터] 새 입찰공고 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE_TIME)})"
            NoticeSource.PRE_SPEC -> "[나라장터] 사전규격 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE)})"
        }
        send(subject, noticeTable(source, notices))
    }

    fun sendAlert(job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String) {
        val subject = "[나라장터 알림 서버] ${job.source.label} 수집 실패 (${consecutiveFailures}회 연속)"
        val html = """
            <p>작업: ${job.source.label}</p>
            <p>연속 실패: ${consecutiveFailures}회</p>
            <p>마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}</p>
            <pre>${escape(error)}</pre>
        """.trimIndent()
        send(subject, html)
    }

    private fun noticeTable(source: NoticeSource, notices: List<Notice>): String {
        val priceLabel = when (source) {
            NoticeSource.BID -> "추정가격"
            NoticeSource.PRE_SPEC -> "배정예산"
        }
        val rows = notices.sortedBy { it.postedAt }.joinToString("\n") { notice ->
            val title = (if (notice.isRevision) "[정정] " else "") + notice.title
            """
            <tr>
              <td><a href="${escape(notice.detailUrl)}">${escape(title)}</a><br><small>${escape(notice.noticeNo)}</small></td>
              <td>${notice.category.label}</td>
              <td>${escape(notice.agency ?: "-")}</td>
              <td>${notice.price?.let { "%,d원".format(it) } ?: "-"}</td>
              <td>${notice.deadlineAt?.format(DISPLAY_DATE_TIME) ?: "-"}</td>
            </tr>
            """.trimIndent()
        }
        return """
            <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>공고명</th><th>업무구분</th><th>수요기관</th><th>$priceLabel</th><th>마감일시</th></tr>
            $rows
            </table>
        """.trimIndent()
    }

    private fun send(subject: String, html: String) {
        val message = mailSender.createMimeMessage()
        MimeMessageHelper(message, false, "UTF-8").apply {
            setFrom(props.mail.from, props.mail.fromName)
            setTo(props.mail.to.toTypedArray())
            setSubject(subject)
            setText(html, true)
        }
        mailSender.send(message)
    }

    private fun escape(text: String): String = HtmlUtils.htmlEscape(text, "UTF-8")

    companion object {
        private val SUBJECT_DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        private val SUBJECT_DATE = DateTimeFormatter.ofPattern("MM/dd")
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
