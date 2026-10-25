package com.narareminder.filter

import org.springframework.data.jpa.repository.JpaRepository

interface FilterTermRepository : JpaRepository<FilterTerm, Long> {
    fun findAllByOrderByIdAsc(): List<FilterTerm>
}

interface FilterSettingRepository : JpaRepository<FilterSetting, Short>
