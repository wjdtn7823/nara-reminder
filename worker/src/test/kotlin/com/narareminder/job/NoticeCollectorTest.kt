package com.narareminder.job

import com.narareminder.PostgresTestConfig
import com.narareminder.cadence.Cadence
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class NoticeCollectorTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
) {
    private val now = Instant.parse("2026-10-31T05:00:00Z")
    private val collector = NoticeCollector(notices, users, items, filters, deliveries, deliveryItems)

    private fun aUser(email: String, status: UserStatus = UserStatus.ACTIVE, filter: UserFilter.() -> Unit = {}): Long {
        val id = requireNotNull(users.save(AppUser(email, "hash", false, now, status)).id)
        filters.save(UserFilter(id).apply(filter))
        return id
    }

    private fun watch(userId: Long, type: WatchItemType, value: String): Long =
        requireNotNull(items.save(WatchItem(userId, type, value, Cadence.IMMEDIATE, now)).id)

    // 테스트 DB 에는 V5 이관 계정도 있으므로 사용자별로 검사한다
    private fun deliveriesOf(userId: Long) = deliveries.findAll().filter { it.userId == userId }

    @Test
    fun `새 공고가 항목에 걸리면 사용자별 발송 대기 1건과 걸린 항목들을 남긴다`() {
        val userId = aUser("a@example.com")
        val keywordId = watch(userId, WatchItemType.KEYWORD, "플랫폼")
        val agencyId = watch(userId, WatchItemType.AGENCY, "테스트기관")

        val result = collector.storeAndMatch(listOf(aNotice(noticeNo = "C1", title = "AI 플랫폼 구축", agency = "테스트기관")), now)

        assertThat(result.newCount).isEqualTo(1)
        val delivery = deliveriesOf(userId).single()
        assertThat(delivery.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(delivery.matchedAt).isEqualTo(now)
        assertThat(deliveryItems.findAllByIdDeliveryIdIn(listOf(delivery.requireId())).map { it.id.watchItemId })
            .containsExactlyInAnyOrder(keywordId, agencyId)
    }

    @Test
    fun `이미 저장된 공고는 다시 매칭하지 않는다`() {
        val userId = aUser("a@example.com")
        watch(userId, WatchItemType.KEYWORD, "플랫폼")
        val notice = aNotice(noticeNo = "C2", title = "AI 플랫폼 구축")
        collector.storeAndMatch(listOf(notice), now)

        val again = collector.storeAndMatch(listOf(notice, notice), now.plusSeconds(300))

        assertThat(again).isEqualTo(NoticeCollector.Result(0, 0))
        assertThat(deliveriesOf(userId)).hasSize(1)
    }

    @Test
    fun `정정공고(차수 증가)는 새 공고로 다시 매칭한다`() {
        val userId = aUser("a@example.com")
        watch(userId, WatchItemType.KEYWORD, "플랫폼")
        collector.storeAndMatch(listOf(aNotice(noticeNo = "C3", noticeOrd = "000", title = "AI 플랫폼 구축")), now)

        val result = collector.storeAndMatch(listOf(aNotice(noticeNo = "C3", noticeOrd = "001", title = "AI 플랫폼 구축")), now)

        assertThat(result.newCount).isEqualTo(1)
        assertThat(deliveriesOf(userId).map { it.notice.noticeOrd }).containsExactlyInAnyOrder("000", "001")
    }

    @Test
    fun `공통 조건에 걸리거나 항목이 없거나 비활성인 사용자에게는 매칭하지 않는다`() {
        val excluded = aUser("excluded@example.com") { update(listOf("구축"), NoticeCategory.entries, null, null) }
        watch(excluded, WatchItemType.KEYWORD, "플랫폼")
        val goodsOnly = aUser("goods@example.com") { update(emptyList(), listOf(NoticeCategory.GOODS), null, null) }
        watch(goodsOnly, WatchItemType.KEYWORD, "플랫폼")
        val noItems = aUser("empty@example.com")
        val disabled = aUser("disabled@example.com", UserStatus.DISABLED)
        watch(disabled, WatchItemType.KEYWORD, "플랫폼")

        collector.storeAndMatch(listOf(aNotice(noticeNo = "C4", title = "AI 플랫폼 구축", category = NoticeCategory.SERVICE)), now)

        listOf(excluded, goodsOnly, noItems, disabled).forEach { assertThat(deliveriesOf(it)).isEmpty() }
    }
}
