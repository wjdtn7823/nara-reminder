package com.narareminder.notice

// 키워드 OR 수요기관으로 포함 여부를 정하고, 제외 키워드와 가격 조건은 AND로 적용한다
fun FilterCriteria.matches(notice: Notice): Boolean =
    isIncluded(notice) &&
        excludeKeywords.none { notice.title.contains(it, ignoreCase = true) } &&
        matchesPrice(notice.price)

private fun FilterCriteria.isIncluded(notice: Notice): Boolean {
    if (keywords.isEmpty() && agencies.isEmpty()) return true
    val keywordHit = keywords.any { notice.title.contains(it, ignoreCase = true) }
    val agencyHit = notice.agency?.let { agency -> agencies.any { agency.contains(it, ignoreCase = true) } } ?: false
    return keywordHit || agencyHit
}

// 가격이 없는 공고는 누락 방지를 위해 통과시킨다
private fun FilterCriteria.matchesPrice(price: Long?): Boolean =
    price == null || ((priceMin == null || price >= priceMin) && (priceMax == null || price <= priceMax))
