package com.narareminder.filter

import com.narareminder.notice.FilterCriteria
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class FilterService(
    private val termRepository: FilterTermRepository,
    private val settingRepository: FilterSettingRepository,
    private val clock: Clock,
) {
    // 수집 파이프라인이 매 실행 호출해 어드민 변경을 다음 수집부터 반영한다
    @Transactional(readOnly = true)
    fun current(): FilterCriteria {
        val terms = termRepository.findAllByOrderByIdAsc()
        val setting = loadSetting()
        fun valuesOf(type: FilterTermType) = terms.filter { it.type == type }.map { it.value }
        return FilterCriteria(
            keywords = valuesOf(FilterTermType.KEYWORD),
            excludeKeywords = valuesOf(FilterTermType.EXCLUDE_KEYWORD),
            agencies = valuesOf(FilterTermType.AGENCY),
            categories = setting.categoryList(),
            priceMin = setting.priceMin,
            priceMax = setting.priceMax,
        )
    }

    private fun loadSetting(): FilterSetting =
        settingRepository.findByIdOrNull(FilterSetting.SINGLE_ROW_ID)
            ?: error("filter_setting 행이 없습니다. V3 마이그레이션을 확인하세요")
}
