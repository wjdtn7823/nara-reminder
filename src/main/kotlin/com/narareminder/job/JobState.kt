package com.narareminder.job

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "job_state")
class JobState(
    @Id
    @Enumerated(EnumType.STRING)
    val jobName: JobName,
    var lastSuccessAt: Instant? = null,
    var consecutiveFailures: Int = 0,
    var alertSent: Boolean = false,
) {
    fun recordSuccess(at: Instant) {
        lastSuccessAt = at
        consecutiveFailures = 0
        alertSent = false
    }

    fun recordFailure() {
        consecutiveFailures++
    }

    // 같은 장애로 알림이 반복 발송되지 않도록 alertSent 로 1회만 허용한다
    fun shouldAlert(threshold: Int): Boolean = !alertSent && consecutiveFailures >= threshold

    fun markAlertSent() {
        alertSent = true
    }
}
