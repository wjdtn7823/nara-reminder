package com.narareminder.cadence

import com.narareminder.support.KST
import com.narareminder.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant

class InvalidCadenceException(message: String) : ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON", message)

// 항목별 발송 주기(cron). api 의 검증·미리보기와 worker 의 발송 판정이 같은 계산을 쓴다
object Cadence {
    const val IMMEDIATE = "0 */5 * * * *"

    // 수집이 5분마다라 더 촘촘하게 보내도 새 공고가 없다
    private val MIN_INTERVAL: Duration = Duration.ofMinutes(5)
    private const val INTERVAL_CHECKS = 10

    fun validate(expr: String, now: Instant): String {
        val trimmed = expr.trim()
        val times = nextTimes(trimmed, now, INTERVAL_CHECKS + 1)
        if (times.isEmpty()) throw InvalidCadenceException("발송 시각이 없는 cron 입니다")
        if (times.zipWithNext().any { (a, b) -> Duration.between(a, b) < MIN_INTERVAL }) {
            throw InvalidCadenceException("발송 간격은 5분 이상이어야 합니다")
        }
        return trimmed
    }

    fun nextTimes(expr: String, after: Instant, count: Int): List<Instant> {
        val cron = parse(expr)
        return generateSequence(cron.next(after.atZone(KST))) { cron.next(it) }
            .take(count)
            .map { it.toInstant() }
            .toList()
    }

    fun nextFire(expr: String, after: Instant): Instant? = parse(expr).next(after.atZone(KST))?.toInstant()

    fun earliestFire(crons: Collection<String>, after: Instant): Instant? = crons.mapNotNull { nextFire(it, after) }.minOrNull()

    // 저장된 다음 발송 시각 없이 매칭 시각만으로 판정하므로, 판정을 건너뛰거나 주기를 바꿔도 누락되지 않는다
    fun isDue(crons: Collection<String>, matchedAt: Instant, now: Instant): Boolean =
        earliestFire(crons, matchedAt)?.let { !it.isAfter(now) } ?: false

    private fun parse(expr: String): CronExpression =
        try {
            CronExpression.parse(expr.trim())
        } catch (e: IllegalArgumentException) {
            throw InvalidCadenceException("cron 형식이 올바르지 않습니다: ${e.message}")
        }
}
