package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class InviteStatus { ACTIVE, EXHAUSTED, EXPIRED, REVOKED }

@Entity
@Table(name = "invite_code")
class InviteCode(
    val code: String,
    val maxUses: Int,
    val expiresAt: Instant,
    val createdBy: Long,
    val createdAt: Instant,
    var usedCount: Int = 0,
    var revokedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun status(now: Instant): InviteStatus = when {
        revokedAt != null -> InviteStatus.REVOKED
        usedCount >= maxUses -> InviteStatus.EXHAUSTED
        !expiresAt.isAfter(now) -> InviteStatus.EXPIRED
        else -> InviteStatus.ACTIVE
    }

    fun isUsable(now: Instant): Boolean = status(now) == InviteStatus.ACTIVE

    fun use() {
        usedCount++
    }

    fun revoke(now: Instant) {
        revokedAt = now
    }
}
