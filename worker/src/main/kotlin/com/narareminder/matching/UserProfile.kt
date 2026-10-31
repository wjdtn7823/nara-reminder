package com.narareminder.matching

import com.narareminder.notice.Notice
import com.narareminder.watch.UserFilter
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemType

data class UserProfile(val userId: Long, val filter: UserFilter, val items: List<WatchItem>) {

    private val excludeKeywords = filter.excludeKeywordList()
    private val categories = filter.categoryList().toSet()

    // 공통 조건은 AND, 항목(키워드·수요기관)은 OR 로 본다
    fun match(notice: Notice): List<WatchItem> {
        if (!passesCommon(notice)) return emptyList()
        return items.filter { it.matches(notice) }
    }

    private fun passesCommon(notice: Notice): Boolean =
        notice.category in categories &&
            excludeKeywords.none { notice.title.contains(it, ignoreCase = true) } &&
            passesPrice(notice.price)

    // 가격이 없는 공고는 누락 방지를 위해 통과시킨다
    private fun passesPrice(price: Long?): Boolean =
        price == null || ((filter.priceMin?.let { price >= it } ?: true) && (filter.priceMax?.let { price <= it } ?: true))

    private fun WatchItem.matches(notice: Notice): Boolean = when (type) {
        WatchItemType.KEYWORD -> notice.title.contains(value, ignoreCase = true)
        WatchItemType.AGENCY -> notice.agency?.contains(value, ignoreCase = true) ?: false
    }
}
