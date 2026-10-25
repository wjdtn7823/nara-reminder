package com.narareminder.filter

import com.narareminder.notice.FilterCriteria
import com.narareminder.notice.NoticeCategory
import com.narareminder.web.NotFoundException
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

    data class FilterView(val terms: List<FilterTerm>, val setting: FilterSetting)

    @Transactional(readOnly = true)
    fun view(): FilterView = FilterView(termRepository.findAllByOrderByIdAsc(), loadSetting())

    @Transactional
    fun addTerm(type: FilterTermType, rawValue: String): FilterTerm {
        val value = normalize(rawValue)
        if (termRepository.existsByTypeAndValueIgnoreCase(type, value)) throw DuplicateFilterTermException(type, value)
        return termRepository.save(FilterTerm(type, value, clock.instant()))
    }

    @Transactional
    fun updateTerm(id: Long, rawValue: String): FilterTerm {
        val term = termRepository.findByIdOrNull(id) ?: throw NotFoundException("필터 항목이 없습니다: $id")
        val value = normalize(rawValue)
        if (termRepository.existsByTypeAndValueIgnoreCaseAndIdNot(term.type, value, id)) {
            throw DuplicateFilterTermException(term.type, value)
        }
        term.value = value
        return term
    }

    @Transactional
    fun deleteTerm(id: Long) {
        if (!termRepository.existsById(id)) throw NotFoundException("필터 항목이 없습니다: $id")
        termRepository.deleteById(id)
    }

    @Transactional
    fun updateSetting(categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?): FilterSetting {
        if (categories.isEmpty()) throw InvalidFilterException("업무구분을 하나 이상 선택하세요")
        if ((priceMin ?: 0) < 0 || (priceMax ?: 0) < 0) throw InvalidFilterException("가격은 0 이상이어야 합니다")
        if (priceMin != null && priceMax != null && priceMin > priceMax) {
            throw InvalidFilterException("최소 가격이 최대 가격보다 클 수 없습니다")
        }
        return loadSetting().apply {
            this.categories = categories.distinct().joinToString(",") { it.name }
            this.priceMin = priceMin
            this.priceMax = priceMax
            this.updatedAt = clock.instant()
        }
    }

    private fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
            throw InvalidFilterException("값은 1~${MAX_VALUE_LENGTH}자여야 합니다")
        }
        return value
    }

    companion object {
        private const val MAX_VALUE_LENGTH = 100
    }

    private fun loadSetting(): FilterSetting =
        settingRepository.findByIdOrNull(FilterSetting.SINGLE_ROW_ID)
            ?: error("filter_setting 행이 없습니다. V3 마이그레이션을 확인하세요")
}
