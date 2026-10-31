package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

enum class UserStatus { ACTIVE, DISABLED }

@Entity
@Table(name = "app_user")
class AppUser(
    val email: String,
    var passwordHash: String?,
    var isAdmin: Boolean,
    val createdAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: UserStatus = UserStatus.ACTIVE,
    var failedCount: Int = 0,
    var lockedUntil: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    // 비밀번호가 없으면 미리 만들어 둔 계정으로, 같은 이메일로 가입해 이어받을 수 있다
    val isClaimed: Boolean
        get() = passwordHash != null

    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    fun recordLoginFailure(now: Instant, maxFailures: Int, lockFor: Duration) {
        failedCount++
        if (failedCount >= maxFailures) {
            lockedUntil = now.plus(lockFor)
            failedCount = 0
        }
    }

    fun recordLoginSuccess() {
        failedCount = 0
        lockedUntil = null
    }

    fun changePassword(hash: String) {
        passwordHash = hash
        recordLoginSuccess()
    }
}
