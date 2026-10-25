package com.narareminder.alert

import com.narareminder.config.NaraProperties
import com.narareminder.job.JobName
import com.narareminder.support.KST
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class DiscordNotifier(props: NaraProperties) {

    private val webhook = props.discord.webhookUrl.takeIf { it.isNotBlank() }?.let { DiscordWebhook(it) }

    // 웹훅이 설정되지 않았으면 보내지 않고 false 를 돌려준다
    fun sendAlert(job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String): Boolean {
        val hook = webhook ?: return false
        hook.send(
            "🚨 **[나라장터 알림 서버] ${job.source.label} 수집 실패 (${consecutiveFailures}회 연속)**\n" +
                "마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}\n" +
                "```\n$error\n```",
        )
        return true
    }

    companion object {
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
