package com.narareminder.cron

import com.narareminder.cadence.Cadence
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

data class CronPreviewResponse(val cron: String, val next: List<Instant>)

// 화면의 주기 편집기와 worker 가 같은 계산(Spring CronExpression)을 쓰도록 미리보기를 서버에서 만든다
@RestController
class CronController(private val clock: Clock) {

    @GetMapping("/api/cron/preview")
    fun preview(@RequestParam expr: String): CronPreviewResponse {
        val now = clock.instant()
        val cron = Cadence.validate(expr, now)
        return CronPreviewResponse(cron, Cadence.nextTimes(cron, now, PREVIEW_COUNT))
    }

    companion object {
        private const val PREVIEW_COUNT = 3
    }
}
