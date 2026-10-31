package com.narareminder.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface EmailTokenRepository : JpaRepository<EmailToken, Long> {
    fun findByTokenHash(tokenHash: String): EmailToken?

    fun existsByEmailAndPurposeAndCreatedAtAfter(email: String, purpose: TokenPurpose, since: Instant): Boolean

    @Modifying
    @Query("delete from EmailToken t where t.usedAt is not null or t.expiresAt < :now")
    fun deleteUsedOrExpired(now: Instant): Int
}
