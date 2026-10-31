package com.narareminder.filter

import com.narareminder.notice.NoticeCategory
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "filter_setting")
class FilterSetting(
    @Id
    val id: Short = SINGLE_ROW_ID,
    var categories: String,
    var priceMin: Long?,
    var priceMax: Long?,
    var updatedAt: Instant,
) {
    fun categoryList(): List<NoticeCategory> =
        categories.split(",").filter { it.isNotBlank() }.map { NoticeCategory.valueOf(it.trim()) }

    companion object {
        const val SINGLE_ROW_ID: Short = 1
    }
}
