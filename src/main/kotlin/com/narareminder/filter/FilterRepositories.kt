package com.narareminder.filter

import org.springframework.data.jpa.repository.JpaRepository

interface FilterTermRepository : JpaRepository<FilterTerm, Long> {
    fun findAllByOrderByIdAsc(): List<FilterTerm>
    fun existsByTypeAndValueIgnoreCase(type: FilterTermType, value: String): Boolean
    fun existsByTypeAndValueIgnoreCaseAndIdNot(type: FilterTermType, value: String, id: Long): Boolean
}

interface FilterSettingRepository : JpaRepository<FilterSetting, Short>
