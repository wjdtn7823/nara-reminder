package com.narareminder.filter

import com.narareminder.notice.NoticeCategory

data class TermResponse(val id: Long, val type: FilterTermType, val value: String)

data class FilterResponse(
    val keywords: List<TermResponse>,
    val excludeKeywords: List<TermResponse>,
    val agencies: List<TermResponse>,
    val categories: List<NoticeCategory>,
    val priceMin: Long?,
    val priceMax: Long?,
)

data class AddTermRequest(val type: FilterTermType, val value: String)
data class UpdateTermRequest(val value: String)
data class UpdateSettingRequest(val categories: List<NoticeCategory>, val priceMin: Long?, val priceMax: Long?)

fun FilterTerm.toResponse() = TermResponse(id = requireNotNull(id), type = type, value = value)

fun FilterService.FilterView.toResponse(): FilterResponse {
    fun of(type: FilterTermType) = terms.filter { it.type == type }.map { it.toResponse() }
    return FilterResponse(
        keywords = of(FilterTermType.KEYWORD),
        excludeKeywords = of(FilterTermType.EXCLUDE_KEYWORD),
        agencies = of(FilterTermType.AGENCY),
        categories = setting.categoryList(),
        priceMin = setting.priceMin,
        priceMax = setting.priceMax,
    )
}
