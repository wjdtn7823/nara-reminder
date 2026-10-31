package com.narareminder.watch

import com.narareminder.notice.NoticeCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_filter")
class UserFilter(
    @Id
    val userId: Long,
    @Column(columnDefinition = "text")
    var excludeKeywords: String = "",
    var categories: String = ALL_CATEGORIES,
    var priceMin: Long? = null,
    var priceMax: Long? = null,
) {
    fun excludeKeywordList(): List<String> = excludeKeywords.split('\n').filter { it.isNotBlank() }

    fun categoryList(): List<NoticeCategory> =
        categories.split(",").filter { it.isNotBlank() }.map { NoticeCategory.valueOf(it.trim()) }

    fun update(excludeKeywords: List<String>, categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?) {
        this.excludeKeywords = excludeKeywords.joinToString("\n")
        this.categories = categories.joinToString(",") { it.name }
        this.priceMin = priceMin
        this.priceMax = priceMax
    }

    companion object {
        val ALL_CATEGORIES: String = NoticeCategory.entries.joinToString(",") { it.name }
    }
}
