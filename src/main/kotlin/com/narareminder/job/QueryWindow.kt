package com.narareminder.job

import com.narareminder.config.NaraProperties
import java.time.Instant

data class QueryWindow(val from: Instant, val to: Instant) {

    companion object {
        // API 반영 지연을 흡수하도록 overlap 만큼 겹쳐 조회하고, 중복은 발송 이력으로 거른다
        fun of(lastSuccessAt: Instant?, now: Instant, settings: NaraProperties.Job): QueryWindow {
            val from = lastSuccessAt
                ?.let { maxOf(it.minus(settings.overlap), now.minus(settings.maxLookback)) }
                ?: now.minus(settings.initialLookback)
            return QueryWindow(from, now)
        }
    }
}
