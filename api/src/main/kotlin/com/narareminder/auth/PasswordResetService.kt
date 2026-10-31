package com.narareminder.auth

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.TokenPurpose
import com.narareminder.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class PasswordResetService(
    private val userRepository: AppUserRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authMails: AuthMails,
    private val clock: Clock,
) {
    // 가입 여부가 드러나지 않도록 계정이 없어도 호출한 쪽에는 같은 응답을 준다
    @Transactional
    fun requestReset(rawEmail: String) {
        val email = rawEmail.trim().lowercase()
        val user = userRepository.findByEmail(email) ?: return
        if (user.status != UserStatus.ACTIVE) return
        val now = clock.instant()
        // 재설정 메일로 Gmail 일일 한도가 소진되지 않도록 같은 주소에는 60초에 한 통만 보낸다
        if (emailTokenRepository.existsByEmailAndPurposeAndCreatedAtAfter(email, TokenPurpose.RESET, now.minus(RESET_THROTTLE))) return
        val token = TokenCodec.newToken()
        emailTokenRepository.save(EmailToken(email, TokenPurpose.RESET, TokenCodec.hash(token), null, null, now.plus(RESET_TTL), now))
        outboxMailRepository.save(authMails.reset(email, token, now))
    }

    @Transactional
    fun reset(token: String, password: String) {
        validatePassword(password)
        val now = clock.instant()
        val emailToken = emailTokenRepository.findByTokenHash(TokenCodec.hash(token))?.takeIf { it.isUsable(TokenPurpose.RESET, now) }
            ?: throw InvalidTokenException()
        val user = userRepository.findByEmail(emailToken.email) ?: throw InvalidTokenException()
        user.changePassword(passwordEncoder.encode(password))
        emailToken.usedAt = now
    }

    companion object {
        private val RESET_TTL: Duration = Duration.ofMinutes(30)
        private val RESET_THROTTLE: Duration = Duration.ofSeconds(60)
    }
}
