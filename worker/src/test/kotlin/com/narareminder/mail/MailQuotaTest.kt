package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.support.KST
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class MailQuotaTest {

    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    private val jobRuns = mockk<JobRunRepository>()
    private val outbox = mockk<OutboxMailRepository>()
    private val discord = mockk<DiscordNotifier> { every { send(any()) } returns true }
    private val quota = MailQuota(jobRuns, outbox, discord)

    private fun sentToday(day: String, alerts: Long, authMails: Long) {
        val since = kst("${day}T00:00:00")
        every { jobRuns.sumSentCountSince(JobName.DELIVERY, since) } returns alerts
        every { outbox.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, since) } returns authMails
    }

    @Test
    fun `400통 이하면 경고하지 않는다`() {
        sentToday("2026-10-31", 380, 20)

        quota.check(kst("2026-10-31T10:00:00"))

        verify(exactly = 0) { discord.send(any()) }
    }

    @Test
    fun `400통을 넘으면 하루에 한 번만 경고한다`() {
        sentToday("2026-10-31", 390, 11)

        quota.check(kst("2026-10-31T10:00:00"))
        quota.check(kst("2026-10-31T11:00:00"))

        verify(exactly = 1) { discord.send(match { it.contains("401통") }) }
    }

    @Test
    fun `다음 날 다시 넘으면 다시 경고한다`() {
        sentToday("2026-10-31", 401, 0)
        sentToday("2026-11-01", 401, 0)

        quota.check(kst("2026-10-31T10:00:00"))
        quota.check(kst("2026-11-01T10:00:00"))

        verify(exactly = 2) { discord.send(any()) }
    }
}
