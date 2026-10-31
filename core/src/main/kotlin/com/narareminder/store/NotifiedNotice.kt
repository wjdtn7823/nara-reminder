package com.narareminder.store

import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeSource
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Embeddable
data class NotifiedNoticeId(
    @Enumerated(EnumType.STRING)
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
) : Serializable

@Entity
@Table(name = "notified_notice")
class NotifiedNotice(
    @EmbeddedId
    val id: NotifiedNoticeId,
    @Column(columnDefinition = "text")
    val title: String,
    val notifiedAt: Instant,
)

fun Notice.notifiedId() = NotifiedNoticeId(source, noticeNo, noticeOrd)
