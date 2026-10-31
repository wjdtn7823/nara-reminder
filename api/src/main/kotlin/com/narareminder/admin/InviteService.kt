package com.narareminder.admin

import com.narareminder.user.InviteCode
import com.narareminder.user.InviteCodeRepository
import com.narareminder.user.InviteStatus
import com.narareminder.web.BadRequestException
import com.narareminder.web.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class InviteResponse(
    val id: Long, val code: String, val status: InviteStatus, val maxUses: Int, val usedCount: Int,
    val expiresAt: Instant, val createdAt: Instant,
)

data class CreateInviteRequest(val maxUses: Int, val validDays: Int)

@Service
class InviteService(
    private val repository: InviteCodeRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun list(): List<InviteResponse> {
        val now = clock.instant()
        return repository.findAllByOrderByIdDesc().map { it.toResponse(now) }
    }

    @Transactional
    fun create(adminId: Long, request: CreateInviteRequest): InviteResponse {
        if (request.maxUses !in 1..MAX_USES) throw BadRequestException("INVALID_INVITE_OPTION", "사용 한도는 1~${MAX_USES}명이어야 합니다")
        if (request.validDays !in 1..MAX_DAYS) throw BadRequestException("INVALID_INVITE_OPTION", "유효 기간은 1~${MAX_DAYS}일이어야 합니다")
        val now = clock.instant()
        val invite = InviteCode(newCode(), request.maxUses, now.plus(Duration.ofDays(request.validDays.toLong())), adminId, now)
        return repository.save(invite).toResponse(now)
    }

    @Transactional
    fun revoke(id: Long): InviteResponse {
        val now = clock.instant()
        val invite = repository.findByIdOrNull(id) ?: throw NotFoundException("초대 코드가 없습니다: $id")
        if (invite.revokedAt == null) invite.revoke(now)
        return invite.toResponse(now)
    }

    // 손으로 옮겨 적기 쉽도록 헷갈리는 글자(0, O, 1, I)를 뺐다
    private fun newCode(): String {
        val chars = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${chars.take(4)}-${chars.drop(4)}"
    }

    private fun InviteCode.toResponse(now: Instant) =
        InviteResponse(requireNotNull(id), code, status(now), maxUses, usedCount, expiresAt, createdAt)

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val MAX_USES = 100
        private const val MAX_DAYS = 30
    }
}
