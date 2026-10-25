package com.narareminder.filter

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class FilterTermType(val label: String) {
    KEYWORD("키워드"),
    EXCLUDE_KEYWORD("제외 키워드"),
    AGENCY("수요기관"),
}

@Entity
@Table(name = "filter_term")
class FilterTerm(
    @Enumerated(EnumType.STRING)
    val type: FilterTermType,
    var value: String,
    val createdAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
