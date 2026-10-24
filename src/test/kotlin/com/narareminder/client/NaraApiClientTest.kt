package com.narareminder.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.narareminder.config.testProperties
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

class NaraApiClientTest {

    private val server = MockWebServer().apply { start() }
    private val from = LocalDateTime.of(2026, 10, 24, 13, 0)
    private val to = LocalDateTime.of(2026, 10, 24, 14, 0)

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(serviceKey: String = "test-key", pageSize: Int = 100) = NaraApiClient(
        WebClient.builder(),
        testProperties(baseUrl = server.url("/1230000").toString(), serviceKey = serviceKey, pageSize = pageSize),
        jacksonObjectMapper(),
    )

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")?.readText()
        ?: error("fixture 없음: $name")

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun page(totalCount: Int, vararg noticeNos: String) = """
        {"response":{"header":{"resultCode":"00","resultMsg":"정상"},"body":{"items":[
        ${noticeNos.joinToString(",") { """{"bidNtceNo":"$it","bidNtceOrd":"000","bidNtceNm":"공고 $it","rgstDt":"2026-10-24 13:10:00"}""" }}
        ],"numOfRows":2,"pageNo":1,"totalCount":$totalCount}}}
    """.trimIndent()

    @Test
    fun `입찰공고 목록을 조회해 Notice로 변환한다`() = runBlocking<Unit> {
        server.enqueue(json(fixture("bid-servc.json")))

        val notices = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices).hasSize(2)
        with(notices[0]) {
            assertThat(noticeNo).isEqualTo("R26BK00000001")
            assertThat(title).isEqualTo("차세대 AI 플랫폼 구축 용역")
            assertThat(price).isEqualTo(150_000_000L)
            assertThat(agency).isEqualTo("조달청")
            assertThat(postedAt).isEqualTo(LocalDateTime.of(2026, 10, 24, 13, 10, 12))
            assertThat(deadlineAt).isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 0))
            assertThat(detailUrl).isEqualTo("https://www.g2b.go.kr/notice/R26BK00000001")
            assertThat(isRevision).isFalse()
        }
        assertThat(notices[1].isRevision).isTrue()

        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/1230000/ad/BidPublicInfoService/getBidPblancListInfoServc")
        assertThat(request.requestUrl?.queryParameter("inqryDiv")).isEqualTo("1")
        assertThat(request.requestUrl?.queryParameter("inqryBgnDt")).isEqualTo("202610241300")
        assertThat(request.requestUrl?.queryParameter("inqryEndDt")).isEqualTo("202610241400")
        assertThat(request.requestUrl?.queryParameter("type")).isEqualTo("json")
    }

    @Test
    fun `빈 값과 소수 가격과 이상한 날짜를 관대하게 매핑한다`() = runBlocking<Unit> {
        server.enqueue(json(fixture("bid-servc.json")))
        server.enqueue(json(fixture("pre-spec-servc.json")))

        val bid = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)[1]
        val preSpec = client().fetch(NoticeSource.PRE_SPEC, NoticeCategory.SERVICE, from, to).single()

        assertThat(bid.price).isNull()
        assertThat(bid.deadlineAt).isNull()
        assertThat(bid.postedAt).isNull()
        assertThat(bid.detailUrl).isEqualTo("https://www.g2b.go.kr")
        assertThat(preSpec.price).isEqualTo(300_000_000L)
    }

    @Test
    fun `사전규격 목록을 사전규격 필드로 변환한다`() = runBlocking<Unit> {
        server.enqueue(json(fixture("pre-spec-servc.json")))

        val notice = client().fetch(NoticeSource.PRE_SPEC, NoticeCategory.SERVICE, from, to).single()

        assertThat(notice.noticeNo).isEqualTo("R26BD00000010")
        assertThat(notice.noticeOrd).isEqualTo("0")
        assertThat(notice.title).isEqualTo("클라우드 전환 컨설팅")
        assertThat(notice.agency).isEqualTo("한국지능정보사회진흥원")
        assertThat(notice.deadlineAt).isEqualTo(LocalDateTime.of(2026, 10, 30, 18, 0))
        assertThat(server.takeRequest().requestUrl?.encodedPath)
            .isEqualTo("/1230000/ao/HrcspSsstndrdInfoService/getPublicPrcureThngInfoServc")
    }

    @Test
    fun `서비스키는 엄격하게 인코딩된다`() = runBlocking<Unit> {
        server.enqueue(json(page(0)))

        client(serviceKey = "a+b/c=").fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(server.takeRequest().path).contains("serviceKey=a%2Bb%2Fc%3D")
    }

    @Test
    fun `전체 건수만큼 페이지를 순회한다`() = runBlocking<Unit> {
        server.enqueue(json(page(3, "N1", "N2")))
        server.enqueue(json(page(3, "N3")))

        val notices = client(pageSize = 2).fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices.map { it.noticeNo }).containsExactly("N1", "N2", "N3")
        assertThat(server.takeRequest().requestUrl?.queryParameter("pageNo")).isEqualTo("1")
        assertThat(server.takeRequest().requestUrl?.queryParameter("pageNo")).isEqualTo("2")
    }

    @Test
    fun `결과코드 03은 0건으로 처리한다`() = runBlocking<Unit> {
        server.enqueue(json("""{"response":{"header":{"resultCode":"03","resultMsg":"NO_DATA"}}}"""))

        assertThat(client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)).isEmpty()
    }

    @Test
    fun `비정상 결과코드는 재시도 없이 실패한다`() {
        server.enqueue(json("""{"response":{"header":{"resultCode":"07","resultMsg":"범위 초과"}}}"""))

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("07")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `JSON이 아닌 응답은 재시도 없이 실패한다`() {
        server.enqueue(
            MockResponse().setBody(
                "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>",
            ),
        )

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `response 키가 없는 인증 오류 JSON은 재시도 없이 실패한다`() {
        server.enqueue(
            json("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnReasonCode":"30"}}}"""),
        )

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `5xx는 재시도 후 성공하면 결과를 돌려준다`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(json(page(1, "N1")))

        val notices = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `5xx가 계속되면 3회 재시도 후 실패한다`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("500")
        assertThat(server.requestCount).isEqualTo(4)
    }
}
