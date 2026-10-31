package com.narareminder.mail

import com.narareminder.config.NaraProperties
import com.narareminder.job.JobName
import com.narareminder.notice.StoredNotice
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
    // 한 사람의 발송 시점에 쌓인 공고를 한 통으로 보낸다
    fun sendDigest(to: String, notices: List<StoredNotice>, now: Instant) {
        if (notices.isEmpty()) return
        val subject = "[나라장터] 새 공고 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE_TIME)})"
        send(listOf(to), subject, noticeTable(notices))
    }

    fun sendHtml(to: String, subject: String, html: String) {
        send(listOf(to), subject, html)
    }

    fun sendAlert(to: List<String>, job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String) {
        if (to.isEmpty()) return
        val subject = "[나라장터 알림 서버] ${job.label} 실패 (${consecutiveFailures}회 연속)"
        val html = """
            <p>작업: ${job.label}</p>
            <p>연속 실패: ${consecutiveFailures}회</p>
            <p>마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}</p>
            <pre>${escape(error)}</pre>
        """.trimIndent()
        send(to, subject, html)
    }

    private fun noticeTable(notices: List<StoredNotice>): String {
        val rows = notices.sortedWith(compareBy({ it.id.source }, { it.postedAt })).joinToString("\n") { notice ->
            val title = escape((if (notice.isRevision) "[정정] " else "") + notice.title)
            val link = notice.detailUrl?.let { "<a href=\"${escape(it)}\">$title</a>" } ?: title
            """
            <tr>
              <td>${notice.id.source.label}</td>
              <td>$link<br><small>${escape(notice.id.noticeNo)}</small></td>
              <td>${notice.category?.label ?: "-"}</td>
              <td>${escape(notice.agency ?: "-")}</td>
              <td>${notice.price?.let { "%,d원".format(it) } ?: "-"}</td>
              <td>${notice.deadlineAt?.format(DISPLAY_DATE_TIME) ?: "-"}</td>
            </tr>
            """.trimIndent()
        }
        return """
            <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>구분</th><th>공고명</th><th>업무구분</th><th>수요기관</th><th>추정가격/배정예산</th><th>마감일시</th></tr>
            $rows
            </table>
        """.trimIndent()
    }

    private fun send(to: List<String>, subject: String, html: String) {
        val message = mailSender.createMimeMessage()
        MimeMessageHelper(message, false, "UTF-8").apply {
            setFrom(props.mail.from, props.mail.fromName)
            setTo(to.toTypedArray())
            setSubject(subject)
            setText(html, true)
        }
        mailSender.send(message)
    }

    private fun escape(text: String): String = HtmlUtils.htmlEscape(text, "UTF-8")

    companion object {
        private val SUBJECT_DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
