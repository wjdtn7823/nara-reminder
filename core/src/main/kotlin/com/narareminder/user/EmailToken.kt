package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class TokenPurpose { VERIFY, RESET }

@Entity
@Table(name = "email_token")
class EmailToken(
    val email: String,
    @Enumerated(EnumType.STRING)
    val purpose: TokenPurpose,
    val tokenHash: String,
    val passwordHash: String?,
    val inviteCodeId: Long?,
    val expiresAt: Instant,
    val createdAt: Instant,
    var usedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun isUsable(purpose: TokenPurpose, now: Instant): Boolean =
        this.purpose == purpose && usedAt == null && expiresAt.isAfter(now)
}
