package com.narareminder.notice

import java.time.LocalDateTime

enum class NoticeSource(val label: String, val servicePath: String, val operationPrefix: String) {
    BID("입찰공고", "ad/BidPublicInfoService", "getBidPblancListInfo"),
    PRE_SPEC("사전규격", "ao/HrcspSsstndrdInfoService", "getPublicPrcureThngInfo"),
}

enum class NoticeCategory(val label: String, val operationSuffix: String) {
    GOODS("물품", "Thng"),
    SERVICE("용역", "Servc"),
    CONSTRUCTION("공사", "Cnstwk"),
    FOREIGN("외자", "Frgcpt"),
}

data class Notice(
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
    val title: String,
    val category: NoticeCategory,
    // BID: 추정가격 / PRE_SPEC: 배정예산
    val price: Long?,
    // BID: 수요기관 / PRE_SPEC: 실수요기관
    val agency: String?,
    val postedAt: LocalDateTime?,
    // BID: 입찰마감 / PRE_SPEC: 의견등록마감
    val deadlineAt: LocalDateTime?,
    val detailUrl: String,
) {
    val isRevision: Boolean
        get() = source == NoticeSource.BID && (noticeOrd.toIntOrNull() ?: 0) > 0
}
