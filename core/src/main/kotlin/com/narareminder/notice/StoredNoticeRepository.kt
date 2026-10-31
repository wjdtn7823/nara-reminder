package com.narareminder.notice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface StoredNoticeRepository : JpaRepository<StoredNotice, NoticeKey> {
    // 발송 대기(delivery)와 걸린 항목은 DB 의 ON DELETE CASCADE 로 함께 지워진다
    @Modifying
    @Query("delete from StoredNotice n where n.collectedAt < :cutoff")
    fun deleteCollectedBefore(cutoff: Instant): Int
}
