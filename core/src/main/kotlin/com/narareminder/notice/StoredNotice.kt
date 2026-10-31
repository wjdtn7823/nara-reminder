package com.narareminder.notice

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime

@Embeddable
data class NoticeKey(
    @Enumerated(EnumType.STRING)
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
) : Serializable

fun Notice.key() = NoticeKey(source, noticeNo, noticeOrd)

@Entity
@Table(name = "notice")
class StoredNotice(
    @EmbeddedId
    val id: NoticeKey,
    @Column(columnDefinition = "text")
    val title: String,
    @Enumerated(EnumType.STRING)
    val category: NoticeCategory?,
    val price: Long?,
    @Column(columnDefinition = "text")
    val agency: String?,
    val postedAt: LocalDateTime?,
    val deadlineAt: LocalDateTime?,
    @Column(columnDefinition = "text")
    val detailUrl: String?,
    val collectedAt: Instant,
) {
    val isRevision: Boolean
        get() = id.source == NoticeSource.BID && (id.noticeOrd.toIntOrNull() ?: 0) > 0

    companion object {
        fun of(notice: Notice, collectedAt: Instant) = StoredNotice(
            id = notice.key(), title = notice.title, category = notice.category, price = notice.price,
            agency = notice.agency, postedAt = notice.postedAt, deadlineAt = notice.deadlineAt,
            detailUrl = notice.detailUrl, collectedAt = collectedAt,
        )
    }
}
