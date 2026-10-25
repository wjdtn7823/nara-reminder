package com.narareminder.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.narareminder.config.NaraProperties
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.support.retrying
import org.springframework.core.NestedExceptionUtils
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class NaraApiClient(
    webClientBuilder: WebClient.Builder,
    private val props: NaraProperties,
    private val objectMapper: ObjectMapper,
) {
    private val webClient = webClientBuilder
        .baseUrl(props.api.baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(props.api.timeout)))
        .build()

    suspend fun fetch(
        source: NoticeSource,
        category: NoticeCategory,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Notice> {
        val notices = mutableListOf<Notice>()
        var pageNo = 1
        while (true) {
            val currentPage = pageNo
            val page = retrying(props.retryDelays, retryOn = { it is NaraApiException && it.retryable }) {
                fetchPage(source, category, from, to, currentPage)
            }
            notices += page.items.map { NoticeMapper.toNotice(source, category, it) }
            if (currentPage * props.api.pageSize >= page.totalCount) return notices
            pageNo++
        }
    }

    private suspend fun fetchPage(
        source: NoticeSource,
        category: NoticeCategory,
        from: LocalDateTime,
        to: LocalDateTime,
        pageNo: Int,
    ): Page {
        val body = try {
            webClient.get()
                .uri { builder ->
                    builder.path("/${source.servicePath}/${source.operationPrefix}${category.operationSuffix}")
                        // 서비스키는 URI 변수로 넘겨야 +, /, = 가 엄격하게 인코딩된다
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", props.api.pageSize)
                        .queryParam("type", "json")
                        .queryParam("inqryDiv", INQUIRY_BY_REGISTERED_AT)
                        .queryParam("inqryBgnDt", from.format(QUERY_DATE_TIME))
                        .queryParam("inqryEndDt", to.format(QUERY_DATE_TIME))
                        .build(props.serviceKey)
                }
                .retrieve()
                .awaitBody<String>()
        } catch (e: WebClientResponseException) {
            throw NaraApiException("HTTP ${e.statusCode.value()}${causeSuffix(e)}", retryable = !e.statusCode.is4xxClientError, cause = e)
        } catch (e: WebClientRequestException) {
            throw NaraApiException("요청 실패: ${causeName(e)}", retryable = true, cause = e)
        }
        return parsePage(body)
    }

    // 예외 자체의 message 에는 serviceKey 가 포함된 전체 URL 이 들어 있으므로 원인 예외의 message 만 사용한다
    private fun rootCause(e: Exception): Throwable? = NestedExceptionUtils.getMostSpecificCause(e).takeIf { it !== e }

    private fun causeSuffix(e: Exception): String =
        rootCause(e)?.let { " (${describe(it)})" }.orEmpty()

    private fun causeName(e: Exception): String = describe(rootCause(e) ?: e, withMessage = false)

    private fun describe(t: Throwable, withMessage: Boolean = true): String {
        val name = t.javaClass.simpleName
        val message = t.message?.takeIf { withMessage }
        return if (message == null) name else "$name: $message"
    }

    // 공공데이터포털은 인증·파라미터 오류를 HTTP 200 + response 키가 없는 별도 JSON(또는 XML)으로 돌려준다
    private fun parsePage(body: String): Page {
        val response = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?.path("response")
            ?.takeUnless { it.isMissingNode }
            ?: throw NaraApiException("비정상 응답: ${body.take(300)}", retryable = false)

        val header = response.path("header")
        when (val code = header.path("resultCode").asText()) {
            RESULT_OK -> Unit
            RESULT_NO_DATA -> return Page(emptyList(), 0)
            else -> throw NaraApiException("결과코드 $code: ${header.path("resultMsg").asText()}", retryable = false)
        }

        val responseBody = response.path("body")
        val items = responseBody.path("items")
        val itemList = when {
            items.isArray -> items.toList()
            items.path("item").isArray -> items.path("item").toList()
            items.path("item").isObject -> listOf(items.path("item"))
            else -> emptyList()
        }
        return Page(itemList, responseBody.path("totalCount").asInt())
    }

    private data class Page(val items: List<JsonNode>, val totalCount: Int)

    companion object {
        // 입찰공고 1건이 약 5KB(113필드)라 기본 256KB 버퍼로는 페이지 하나도 못 받는다
        private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val INQUIRY_BY_REGISTERED_AT = "1"
        private const val RESULT_OK = "00"
        private const val RESULT_NO_DATA = "03"
        private val QUERY_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
