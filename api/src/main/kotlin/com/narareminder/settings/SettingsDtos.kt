package com.narareminder.settings

import com.narareminder.notice.NoticeCategory
import com.narareminder.watch.UserFilter

data class SettingsRequest(
    val excludeKeywords: List<String>, val categories: List<NoticeCategory>, val priceMin: Long?, val priceMax: Long?,
)

data class SettingsResponse(
    val excludeKeywords: List<String>, val categories: List<NoticeCategory>, val priceMin: Long?, val priceMax: Long?,
)

fun UserFilter.toResponse() = SettingsResponse(excludeKeywordList(), categoryList(), priceMin, priceMax)
