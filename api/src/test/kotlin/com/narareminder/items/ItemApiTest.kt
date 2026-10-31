package com.narareminder.items

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.cadence.Cadence
import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeKey
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.support.authAs
import com.narareminder.support.withXsrf
import com.narareminder.support.xsrfCookie
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class ItemApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
) {
    private val me by lazy { requireNotNull(accountService.create("me@example.com", "hash", admin = false).id) }
    private val other by lazy { requireNotNull(accountService.create("other@example.com", "hash", admin = false).id) }

    private fun create(json: String, userId: Long = me) = mockMvc.post("/api/items") {
        with(authAs(userId))
        withXsrf(mockMvc.xsrfCookie())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun itemOf(userId: Long, value: String, cron: String = Cadence.IMMEDIATE): Long =
        requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, value, cron, Instant.now())).id)

    @Test
    fun `항목을 추가하면 기본 주기는 즉시이고 다음 발송 시각과 함께 목록에 나온다`() {
        create("""{"type":"KEYWORD","value":"  플랫폼  "}""").andExpect {
            status { isCreated() }
            jsonPath("$.value") { value("플랫폼") }
            jsonPath("$.cron") { value("0 */5 * * * *") }
            jsonPath("$.nextSendAt") { value(notNullValue()) }
        }

        mockMvc.get("/api/items") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].type") { value("KEYWORD") }
        }
    }

    @Test
    fun `값이 비었거나 100자를 넘으면 400`() {
        create("""{"type":"KEYWORD","value":"   "}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_VALUE") }
        }
        create("""{"type":"KEYWORD","value":"${"가".repeat(101)}"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `같은 종류에서 대소문자만 다른 값은 409, 다른 종류면 허용`() {
        create("""{"type":"KEYWORD","value":"ODA"}""").andExpect { status { isCreated() } }

        create("""{"type":"KEYWORD","value":"oda"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE") }
        }
        create("""{"type":"AGENCY","value":"ODA"}""").andExpect { status { isCreated() } }
    }

    @Test
    fun `형식이 틀리거나 5분보다 촘촘한 cron 은 400`() {
        create("""{"type":"KEYWORD","value":"플랫폼","cron":"매일"}""").andExpect { jsonPath("$.code") { value("INVALID_CRON") } }
        create("""{"type":"KEYWORD","value":"플랫폼","cron":"0 * * * * *"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("발송 간격은 5분 이상이어야 합니다") }
        }
    }

    @Test
    fun `값과 주기를 고치고 지울 수 있다`() {
        val id = itemOf(me, "플랫폼")

        mockMvc.patch("/api/items/$id") {
            with(authAs(me))
            withXsrf(mockMvc.xsrfCookie())
            contentType = MediaType.APPLICATION_JSON
            content = """{"value":"클라우드","cron":"0 0 9 * * MON-FRI"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value") { value("클라우드") }
            jsonPath("$.cron") { value("0 0 9 * * MON-FRI") }
        }

        mockMvc.delete("/api/items/$id") {
            with(authAs(me))
            withXsrf(mockMvc.xsrfCookie())
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `남의 항목은 조회·수정·삭제 모두 404`() {
        val id = itemOf(other, "플랫폼")

        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect { status { isNotFound() } }
        mockMvc.patch("/api/items/$id") {
            with(authAs(me))
            withXsrf(mockMvc.xsrfCookie())
            contentType = MediaType.APPLICATION_JSON
            content = """{"value":"가로채기"}"""
        }.andExpect { status { isNotFound() } }
        mockMvc.delete("/api/items/$id") {
            with(authAs(me))
            withXsrf(mockMvc.xsrfCookie())
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `상세에는 최근 7일 통계와 최근 걸린 공고의 상태·예정 발송 시각이 나온다`() {
        val id = itemOf(me, "타당성", cron = "0 0 9 * * *")
        val matchedAt = Instant.now().minus(Duration.ofHours(1))
        fun notice(no: String, ord: String = "000") = notices.save(
            StoredNotice(
                NoticeKey(NoticeSource.BID, no, ord), "도로 타당성조사 $no", NoticeCategory.SERVICE, 240_000_000, "강원특별자치도",
                null, LocalDateTime.of(2026, 11, 5, 10, 0), "https://g2b/$no", matchedAt,
            ),
        )
        val sent = deliveries.save(Delivery(me, notice("R1").id, matchedAt.minusSeconds(60)).apply { markSent(matchedAt) })
        val pending = deliveries.save(Delivery(me, notice("R2", "001").id, matchedAt))
        deliveryItems.saveAllAndFlush(listOf(sent, pending).map { DeliveryItem(DeliveryItemId(it.requireId(), id)) })

        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.item.value") { value("타당성") }
            jsonPath("$.stats.matched") { value(2) }
            jsonPath("$.stats.sent") { value(1) }
            jsonPath("$.stats.pending") { value(1) }
            jsonPath("$.recent.length()") { value(2) }
            jsonPath("$.recent[0].status") { value("PENDING") }
            jsonPath("$.recent[0].revision") { value(true) }
            jsonPath("$.recent[0].scheduledAt") { value(notNullValue()) }
            jsonPath("$.recent[0].deadlineAt") { value(startsWith("2026-11-05T10:00")) }
            jsonPath("$.recent[1].status") { value("SENT") }
            jsonPath("$.recent[1].scheduledAt") { value(nullValue()) }
            jsonPath("$.recent[1].agency") { value("강원특별자치도") }
        }
    }

    @Test
    fun `로그인하지 않으면 401`() {
        mockMvc.get("/api/items").andExpect { status { isUnauthorized() } }
    }
}
