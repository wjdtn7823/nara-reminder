package com.narareminder.notice

import java.time.LocalDateTime

fun aNotice(
    noticeNo: String = "R26BK00000001",
    noticeOrd: String = "000",
    title: String = "AI 플랫폼 구축",
    source: NoticeSource = NoticeSource.BID,
    category: NoticeCategory = NoticeCategory.SERVICE,
    price: Long? = 100_000_000,
    agency: String? = "조달청",
    postedAt: LocalDateTime? = LocalDateTime.of(2026, 10, 24, 13, 0),
    deadlineAt: LocalDateTime? = LocalDateTime.of(2026, 11, 3, 10, 0),
    detailUrl: String = "https://www.g2b.go.kr/notice/$noticeNo",
) = Notice(source, noticeNo, noticeOrd, title, category, price, agency, postedAt, deadlineAt, detailUrl)
