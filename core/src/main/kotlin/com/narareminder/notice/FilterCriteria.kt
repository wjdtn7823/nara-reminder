package com.narareminder.notice

data class FilterCriteria(
    val keywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val categories: List<NoticeCategory> = NoticeCategory.entries,
    val priceMin: Long? = null,
    val priceMax: Long? = null,
    val agencies: List<String> = emptyList(),
)
