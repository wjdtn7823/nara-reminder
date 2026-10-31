package com.narareminder.delivery

import com.narareminder.PostgresTestConfig
import com.narareminder.alert.DiscordNotifier
import com.narareminder.cadence.Cadence
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import com.narareminder.mail.MailNotifier
import com.narareminder.mail.MailQuota
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeKey
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class DeliveryDispatcherTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
    private val jobRuns: JobRunRepository,
) {
    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    // 2026-10-30(금) 14:03 KST 에 매칭된 공고들
    private val matchedAt = kst("2026-10-30T14:03:00")
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val mailQuota = mockk<MailQuota>(relaxed = true)

    private fun dispatchAt(now: Instant) =
        DeliveryDispatcher(deliveries, deliveryItems, items, notices, users, mailNotifier, discordNotifier, jobRuns, mailQuota, Clock.fixed(now, KST))
            .dispatch()

    private fun aUser(email: String): Long {
        val id = requireNotNull(users.save(AppUser(email, "hash", false, matchedAt)).id)
        filters.save(UserFilter(id))
        return id
    }

    private fun watch(userId: Long, value: String, cron: String): WatchItem =
        items.save(WatchItem(userId, WatchItemType.KEYWORD, value, cron, matchedAt))

    private fun pending(userId: Long, noticeNo: String, vararg watched: WatchItem): Delivery {
        val key = NoticeKey(NoticeSource.BID, noticeNo, "000")
        if (!notices.existsById(key)) {
            notices.save(StoredNotice(key, "공고 $noticeNo", NoticeCategory.SERVICE, 1_000, "조달청", null, null, "https://g2b/$noticeNo", matchedAt))
        }
        val delivery = deliveries.save(Delivery(userId, key, matchedAt))
        deliveryItems.saveAll(watched.map { DeliveryItem(DeliveryItemId(delivery.requireId(), requireNotNull(it.id))) })
        return delivery
    }

    private fun reload(delivery: Delivery): Delivery? = deliveries.findById(delivery.requireId()).orElse(null)

    private fun deliveryRuns() = jobRuns.findAll().filter { it.jobName == JobName.DELIVERY }

    @Test
    fun `주기가 오지 않은 공고는 보내지 않고 실행 이력도 남기지 않는다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "플랫폼", Cadence.IMMEDIATE))

        dispatchAt(kst("2026-10-30T14:04:30"))

        verify(exactly = 0) { mailNotifier.sendDigest(any(), any(), any()) }
        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(deliveryRuns()).isEmpty()
    }

    @Test
    fun `주기가 되면 사용자별로 한 통에 모아 보내고 SENT 로 바꾼다`() {
        val userId = aUser("a@example.com")
        val item = watch(userId, "플랫폼", Cadence.IMMEDIATE)
        val first = pending(userId, "D1", item)
        val second = pending(userId, "D2", item)
        val now = kst("2026-10-30T14:05:10")

        dispatchAt(now)

        verify(exactly = 1) { mailNotifier.sendDigest("a@example.com", match { it.size == 2 }, now) }
        assertThat(listOf(reload(first)?.status, reload(second)?.status)).containsOnly(DeliveryStatus.SENT)
        val run = deliveryRuns().single()
        assertThat(listOf(run.matchedCount, run.sentCount)).containsExactly(2, 1)
        assertThat(run.status).isEqualTo(JobRunStatus.SUCCESS)
        verify { mailQuota.check(now) }
    }

    @Test
    fun `여러 항목에 걸린 공고는 가장 빠른 주기로 나간다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "아침", "0 0 9 * * *"), watch(userId, "즉시", Cadence.IMMEDIATE))

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `판정을 건너뛰어도 나중 판정에서 보낸다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "아침", "0 0 9 * * *"))

        dispatchAt(kst("2026-10-31T15:00:00"))

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `주기를 바꾸면 다음 판정부터 바로 반영된다`() {
        val userId = aUser("a@example.com")
        val item = watch(userId, "아침", "0 0 9 * * *")
        val delivery = pending(userId, "D1", item)
        val now = kst("2026-10-30T14:05:10")
        dispatchAt(now)
        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.PENDING)

        item.cron = Cadence.IMMEDIATE
        items.save(item)
        dispatchAt(now)

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `걸린 항목이 모두 지워진 대기 공고는 지운다`() {
        val userId = aUser("a@example.com")
        val orphan = pending(userId, "D1")

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(orphan)).isNull()
        verify(exactly = 0) { mailNotifier.sendDigest(any(), any(), any()) }
    }

    @Test
    fun `한 사람의 발송이 실패해도 다른 사람은 보낸다`() {
        val a = aUser("a@example.com")
        val b = aUser("b@example.com")
        val failing = pending(a, "D1", watch(a, "플랫폼", Cadence.IMMEDIATE))
        val ok = pending(b, "D1", watch(b, "플랫폼", Cadence.IMMEDIATE))
        every { mailNotifier.sendDigest("a@example.com", any(), any()) } throws IllegalStateException("SMTP 오류")

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(failing)?.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(reload(failing)?.attempts).isEqualTo(1)
        assertThat(reload(ok)?.status).isEqualTo(DeliveryStatus.SENT)
        val run = deliveryRuns().single()
        assertThat(run.status).isEqualTo(JobRunStatus.FAILURE)
        assertThat(run.error).contains("a@example.com 발송 실패: SMTP 오류")
    }

    @Test
    fun `3번 실패하면 FAILED 로 바꾸고 디스코드로 한 번 알린다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "플랫폼", Cadence.IMMEDIATE))
        every { mailNotifier.sendDigest(any(), any(), any()) } throws IllegalStateException("SMTP 오류")
        val now = kst("2026-10-30T14:05:10")

        repeat(3) { dispatchAt(now) }

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.FAILED)
        verify(exactly = 1) { discordNotifier.send(match { it.contains("a@example.com") }) }
    }
}
