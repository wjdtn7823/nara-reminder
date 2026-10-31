package com.narareminder.job

import com.narareminder.config.NaraProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class QueryWindowTest {

    private val settings = NaraProperties.Job(
        overlap = Duration.ofMinutes(30),
        initialLookback = Duration.ofHours(1),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = 6,
    )
    private val now = Instant.parse("2026-10-24T05:00:00Z")

    @Test
    fun `최초 실행은 initialLookback 만큼 거슬러 조회한다`() {
        assertThat(QueryWindow.of(null, now, settings))
            .isEqualTo(QueryWindow(now.minus(Duration.ofHours(1)), now))
    }

    @Test
    fun `정상 실행은 마지막 성공 시각에서 overlap 만큼 겹쳐 조회한다`() {
        val lastSuccess = now.minus(Duration.ofMinutes(5))

        assertThat(QueryWindow.of(lastSuccess, now, settings))
            .isEqualTo(QueryWindow(lastSuccess.minus(Duration.ofMinutes(30)), now))
    }

    @Test
    fun `장기 중단 후에는 maxLookback 까지만 조회한다`() {
        val lastSuccess = now.minus(Duration.ofDays(30))

        assertThat(QueryWindow.of(lastSuccess, now, settings))
            .isEqualTo(QueryWindow(now.minus(Duration.ofDays(7)), now))
    }
}
