package com.narareminder.auth

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.InviteCodeRepository
import com.narareminder.user.TokenPurpose
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

fun normalizeInviteCode(raw: String): String = raw.trim().uppercase()

@Service
class SignupService(
    private val inviteCodeRepository: InviteCodeRepository,
    private val userRepository: AppUserRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val accountService: AccountService,
    private val passwordEncoder: PasswordEncoder,
    private val authMails: AuthMails,
    private val clock: Clock,
) {
    // 이메일을 확인하기 전에는 계정을 만들지 않고, 확인 토큰에 비밀번호 해시와 초대 코드를 담아 둔다
    @Transactional
    fun requestSignup(rawCode: String, rawEmail: String, password: String) {
        val email = normalizeEmail(rawEmail)
        validatePassword(password)
        val now = clock.instant()
        val invite = inviteCodeRepository.findByCode(normalizeInviteCode(rawCode))?.takeIf { it.isUsable(now) }
            ?: throw InvalidInviteException()
        if (userRepository.findByEmail(email)?.isClaimed == true) throw AlreadyRegisteredException()

        val token = TokenCodec.newToken()
        emailTokenRepository.save(
            EmailToken(email, TokenPurpose.VERIFY, TokenCodec.hash(token), passwordEncoder.encode(password), invite.id, now.plus(VERIFY_TTL), now),
        )
        outboxMailRepository.save(authMails.verify(email, token, now))
    }

    @Transactional
    fun verify(token: String) {
        val now = clock.instant()
        val emailToken = emailTokenRepository.findByTokenHash(TokenCodec.hash(token))?.takeIf { it.isUsable(TokenPurpose.VERIFY, now) }
            ?: throw InvalidTokenException()
        val passwordHash = emailToken.passwordHash ?: throw InvalidTokenException()
        val invite = emailToken.inviteCodeId?.let { inviteCodeRepository.findForUpdate(it) }?.takeIf { it.isUsable(now) }
            ?: throw InvalidInviteException()

        val existing = userRepository.findByEmail(emailToken.email)
        when {
            existing == null -> accountService.create(emailToken.email, passwordHash, admin = false)
            !existing.isClaimed -> existing.changePassword(passwordHash)
            else -> throw AlreadyRegisteredException()
        }
        invite.use()
        emailToken.usedAt = now
    }

    companion object {
        private val VERIFY_TTL: Duration = Duration.ofHours(24)
    }
}
