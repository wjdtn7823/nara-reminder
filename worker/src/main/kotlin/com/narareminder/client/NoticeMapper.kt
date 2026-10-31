package com.narareminder.client

import com.fasterxml.jackson.databind.JsonNode
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 항목 하나의 형식 이상으로 업무구분 전체가 실패하지 않도록 필드 단위로 null 처리한다
internal object NoticeMapper {

    private const val G2B_URL = "https://www.g2b.go.kr"
    private val RESPONSE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

    fun toNotice(source: NoticeSource, category: NoticeCategory, item: JsonNode): Notice = when (source) {
        NoticeSource.BID -> Notice(
            source = source,
            noticeNo = item.text("bidNtceNo"),
            noticeOrd = item.text("bidNtceOrd"),
            title = item.text("bidNtceNm"),
            category = category,
            price = item.long("presmptPrce"),
            agency = item.textOrNull("dminsttNm"),
            postedAt = item.dateTime("rgstDt"),
            deadlineAt = item.dateTime("bidClseDt"),
            detailUrl = item.textOrNull("bidNtceDtlUrl") ?: G2B_URL,
        )
        NoticeSource.PRE_SPEC -> Notice(
            source = source,
            noticeNo = item.text("bfSpecRgstNo"),
            noticeOrd = "0",
            title = item.text("prdctClsfcNoNm"),
            category = category,
            price = item.long("asignBdgtAmt"),
            agency = item.textOrNull("rlDminsttNm"),
            postedAt = item.dateTime("rgstDt"),
            deadlineAt = item.dateTime("opninRgstClseDt"),
            detailUrl = G2B_URL,
        )
    }

    private fun JsonNode.text(field: String): String =
        path(field).let { if (it.isNull || it.isMissingNode) "" else it.asText().trim() }

    private fun JsonNode.textOrNull(field: String): String? = text(field).ifBlank { null }

    private fun JsonNode.long(field: String): Long? = textOrNull(field)?.toBigDecimalOrNull()?.toLong()

    private fun JsonNode.dateTime(field: String): LocalDateTime? =
        textOrNull(field)?.let { runCatching { LocalDateTime.parse(it, RESPONSE_DATE_TIME) }.getOrNull() }
}
