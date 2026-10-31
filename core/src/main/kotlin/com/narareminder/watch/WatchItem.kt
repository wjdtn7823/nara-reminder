package com.narareminder.watch

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class WatchItemType(val label: String) {
    KEYWORD("키워드"),
    AGENCY("수요기관"),
}

@Entity
@Table(name = "watch_item")
class WatchItem(
    val userId: Long,
    @Enumerated(EnumType.STRING)
    val type: WatchItemType,
    var value: String,
    var cron: String,
    val createdAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
