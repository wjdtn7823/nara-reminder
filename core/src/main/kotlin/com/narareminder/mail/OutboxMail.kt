package com.narareminder.mail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class OutboxStatus { PENDING, SENT, FAILED }

// api 가 넣고 worker 가 보내는 인증 메일 (api 에는 SMTP 비밀번호를 두지 않는다)
@Entity
@Table(name = "outbox_mail")
class OutboxMail(
    val toAddress: String,
    val subject: String,
    @Column(columnDefinition = "text")
    val html: String,
    val createdAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: OutboxStatus = OutboxStatus.PENDING,
    var attempts: Int = 0,
    var sentAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun markSent(at: Instant) {
        status = OutboxStatus.SENT
        sentAt = at
    }

    // 실패 횟수가 한도에 닿으면 FAILED 로 바꾸고 true 를 돌려준다
    fun recordFailure(maxAttempts: Int): Boolean {
        attempts++
        if (attempts < maxAttempts) return false
        status = OutboxStatus.FAILED
        return true
    }
}
