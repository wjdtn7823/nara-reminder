package com.narareminder.mail

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface OutboxMailRepository : JpaRepository<OutboxMail, Long> {
    fun findTop20ByStatusOrderByIdAsc(status: OutboxStatus): List<OutboxMail>
    fun countByStatusAndSentAtGreaterThanEqual(status: OutboxStatus, since: Instant): Long

    @Modifying
    @Query("delete from OutboxMail m where m.status <> :status and m.createdAt < :cutoff")
    fun deleteByStatusNotAndCreatedAtBefore(status: OutboxStatus, cutoff: Instant): Int
}
