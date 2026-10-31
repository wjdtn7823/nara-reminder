package com.narareminder.delivery

import com.narareminder.notice.NoticeKey
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class DeliveryStatus { PENDING, SENT, FAILED }

@Entity
@Table(name = "delivery")
class Delivery(
    val userId: Long,
    @Embedded
    val notice: NoticeKey,
    val matchedAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: DeliveryStatus = DeliveryStatus.PENDING,
    var attempts: Int = 0,
    var sentAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun requireId(): Long = requireNotNull(id) { "저장되지 않은 발송 대기입니다" }

    fun markSent(at: Instant) {
        status = DeliveryStatus.SENT
        sentAt = at
    }

    // 실패 횟수가 한도에 닿으면 FAILED 로 바꾸고 true 를 돌려준다
    fun recordFailure(maxAttempts: Int): Boolean {
        attempts++
        if (attempts < maxAttempts) return false
        status = DeliveryStatus.FAILED
        return true
    }
}
