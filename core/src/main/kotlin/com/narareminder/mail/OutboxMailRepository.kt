package com.narareminder.mail

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface OutboxMailRepository : JpaRepository<OutboxMail, Long> {
    fun findTop20ByStatusOrderByIdAsc(status: OutboxStatus): List<OutboxMail>
    fun countByStatusAndSentAtGreaterThanEqual(status: OutboxStatus, since: Instant): Long
    fun deleteByStatusNotAndCreatedAtBefore(status: OutboxStatus, cutoff: Instant): Long
}
