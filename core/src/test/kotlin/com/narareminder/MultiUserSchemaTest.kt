package com.narareminder

import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.mail.OutboxMail
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.mail.OutboxStatus
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.notice.key
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class MultiUserSchemaTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
    private val outbox: OutboxMailRepository,
    private val em: TestEntityManager,
) {
    private val now = Instant.parse("2026-10-31T05:00:00Z")

    private fun aUser(email: String = "a@example.com"): Long =
        requireNotNull(users.save(AppUser(email, "hash", false, now)).id)

    private fun pendingDelivery(userId: Long, itemId: Long): Long {
        val notice = notices.save(StoredNotice.of(aNotice(noticeNo = "S1"), now))
        val delivery = deliveries.save(Delivery(userId, notice.id, now))
        deliveryItems.save(DeliveryItem(DeliveryItemId(delivery.requireId(), itemId)))
        em.flush()
        return delivery.requireId()
    }

    @Test
    fun `사용자·항목·공통 조건을 저장하고 조회한다`() {
        val userId = aUser()
        filters.save(UserFilter(userId).apply { update(listOf("교육", "청소"), listOf(NoticeCategory.SERVICE), 1_000, null) })
        items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now))
        em.flush()
        em.clear()

        assertThat(users.findByEmail("a@example.com")?.isClaimed).isTrue()
        val filter = filters.findById(userId).orElseThrow()
        assertThat(filter.excludeKeywordList()).containsExactly("교육", "청소")
        assertThat(filter.categoryList()).containsExactly(NoticeCategory.SERVICE)
        assertThat(items.findAllByUserIdOrderByIdAsc(userId).map { it.value }).containsExactly("플랫폼")
    }

    @Test
    fun `같은 사용자의 같은 종류 항목은 대소문자만 달라도 중복 저장할 수 없다`() {
        val userId = aUser()
        items.saveAndFlush(WatchItem(userId, WatchItemType.KEYWORD, "ODA", "0 */5 * * * *", now))

        assertThatThrownBy { items.saveAndFlush(WatchItem(userId, WatchItemType.KEYWORD, "oda", "0 */5 * * * *", now)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `항목을 지우면 발송 대기에 걸린 항목도 지워진다`() {
        val userId = aUser()
        val itemId = requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now)).id)
        val deliveryId = pendingDelivery(userId, itemId)
        em.clear()

        items.deleteById(itemId)
        em.flush()
        em.clear()

        assertThat(deliveryItems.findAllByIdDeliveryIdIn(listOf(deliveryId))).isEmpty()
        assertThat(deliveries.findById(deliveryId).orElseThrow().status).isEqualTo(DeliveryStatus.PENDING)
    }

    @Test
    fun `오래된 공고를 지우면 그 공고의 발송 대기도 지워진다`() {
        val userId = aUser()
        val itemId = requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now)).id)
        val deliveryId = pendingDelivery(userId, itemId)
        em.clear()

        val deleted = notices.deleteCollectedBefore(now.plusSeconds(1))
        em.clear()

        assertThat(deleted).isEqualTo(1)
        assertThat(deliveries.findById(deliveryId)).isEmpty()
        assertThat(notices.findById(aNotice(noticeNo = "S1").key())).isEmpty()
    }

    @Test
    fun `outbox 메일을 오래된 순으로 꺼낸다`() {
        outbox.save(OutboxMail("a@example.com", "첫째", "<p>1</p>", now))
        outbox.save(OutboxMail("b@example.com", "둘째", "<p>2</p>", now))
        outbox.save(OutboxMail("c@example.com", "보냄", "<p>3</p>", now).apply { markSent(now) })

        assertThat(outbox.findTop20ByStatusOrderByIdAsc(OutboxStatus.PENDING).map { it.subject }).containsExactly("첫째", "둘째")
        assertThat(outbox.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, now)).isEqualTo(1)
    }

    @Test
    fun `실패가 한도에 닿으면 FAILED 로 바뀐다`() {
        val delivery = Delivery(1, aNotice().key(), now)

        assertThat(delivery.recordFailure(3)).isFalse()
        assertThat(delivery.recordFailure(3)).isFalse()
        assertThat(delivery.recordFailure(3)).isTrue()
        assertThat(delivery.status).isEqualTo(DeliveryStatus.FAILED)
    }
}
