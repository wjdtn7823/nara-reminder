package com.narareminder.job

import com.narareminder.PostgresTestConfig
import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.mail.OutboxMail
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.notice.key
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.TokenPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class RetentionCleanupTest @Autowired constructor(
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val users: AppUserRepository,
    private val jobRuns: JobRunRepository,
    private val tokens: EmailTokenRepository,
    private val outbox: OutboxMailRepository,
    private val em: TestEntityManager,
) {
    private val now = Instant.parse("2026-10-31T00:00:00Z")
    private val old = now.minus(Duration.ofDays(91))
    private val recent = now.minus(Duration.ofDays(1))
    private val cleanup = RetentionCleanup(notices, jobRuns, tokens, outbox, Clock.fixed(now, KST))

    private fun run(at: Instant) = JobRun(
        jobName = JobName.BID_POLLING, startedAt = at, finishedAt = at, windowFrom = at, windowTo = at,
        fetchedCount = 0, matchedCount = 0, sentCount = 0, status = JobRunStatus.SUCCESS, error = null,
    )

    private fun token(hash: String, expiresAt: Instant, usedAt: Instant? = null) =
        EmailToken("a@example.com", TokenPurpose.RESET, hash, null, null, expiresAt, old, usedAt)

    @Test
    fun `90일 지난 공고·발송 대기·실행 이력과 쓴·만료된 토큰, 끝난 지 7일 지난 outbox 를 지운다`() {
        val userId = requireNotNull(users.save(AppUser("a@example.com", "hash", false, old)).id)
        val oldNotice = notices.save(StoredNotice.of(aNotice(noticeNo = "OLD"), old))
        notices.save(StoredNotice.of(aNotice(noticeNo = "NEW"), recent))
        val oldDelivery = deliveries.save(Delivery(userId, oldNotice.id, old))
        jobRuns.saveAll(listOf(run(old), run(recent)))
        tokens.saveAll(listOf(token("used", now.plusSeconds(60), usedAt = recent), token("expired", recent), token("valid", now.plusSeconds(60))))
        outbox.save(OutboxMail("a@example.com", "옛 메일", "<p/>", old).apply { markSent(old) })
        outbox.save(OutboxMail("a@example.com", "대기", "<p/>", old))
        em.flush()
        em.clear()

        val result = cleanup.purge()
        em.clear()

        assertThat(result).isEqualTo(RetentionCleanup.Result(notices = 1, runs = 1, tokens = 2, outbox = 1))
        assertThat(notices.findById(aNotice(noticeNo = "OLD").key())).isEmpty()
        assertThat(notices.findById(aNotice(noticeNo = "NEW").key())).isPresent()
        assertThat(deliveries.findById(oldDelivery.requireId())).isEmpty()
        assertThat(tokens.findAll().map { it.tokenHash }).containsExactly("valid")
        assertThat(outbox.findAll().map { it.subject }).containsExactly("대기")
    }
}
