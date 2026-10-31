package com.narareminder.auth

import com.narareminder.security.AppPrincipal
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) {
    // 실패 횟수 기록이 롤백되지 않도록 인증 실패 예외는 커밋한다
    @Transactional(noRollbackFor = [InvalidCredentialsException::class, AccountLockedException::class])
    fun authenticate(rawEmail: String, password: String): AppPrincipal {
        val user = userRepository.findByEmail(rawEmail.trim().lowercase()) ?: throw InvalidCredentialsException()
        val now = clock.instant()
        if (user.isLocked(now)) throw AccountLockedException()
        val hash = user.passwordHash
        if (hash == null || user.status != UserStatus.ACTIVE || !passwordEncoder.matches(password, hash)) {
            user.recordLoginFailure(now, MAX_FAILURES, LOCK_DURATION)
            throw InvalidCredentialsException()
        }
        user.recordLoginSuccess()
        return AppPrincipal(requireNotNull(user.id), user.email, user.isAdmin)
    }

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCK_DURATION: Duration = Duration.ofMinutes(15)
    }
}
