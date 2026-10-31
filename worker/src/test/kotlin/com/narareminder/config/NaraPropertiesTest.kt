package com.narareminder.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class NaraPropertiesTest {

    private val required = mapOf(
        "nara.service-key" to "key",
        "nara.mail.from" to "bot@example.com",
        "nara.bid.overlap" to "30m",
        "nara.bid.initial-lookback" to "1h",
        "nara.bid.max-lookback" to "7d",
        "nara.bid.alert-after-failures" to "6",
        "nara.pre-spec.overlap" to "1h",
        "nara.pre-spec.initial-lookback" to "24h",
        "nara.pre-spec.max-lookback" to "7d",
        "nara.pre-spec.alert-after-failures" to "1",
    )

    private fun bind(vararg extra: Pair<String, String>): NaraProperties =
        Binder(MapConfigurationPropertySource(required + extra))
            .bind("nara", NaraProperties::class.java)
            .get()

    @Test
    fun `필수 설정과 기간 표기를 바인딩한다`() {
        val props = bind()

        assertThat(props.mail.fromName).isEqualTo("나라장터 알림")
        assertThat(props.bid.overlap).isEqualTo(Duration.ofMinutes(30))
        assertThat(props.bid.maxLookback).isEqualTo(Duration.ofDays(7))
        assertThat(props.preSpec.alertAfterFailures).isEqualTo(1)
        assertThat(props.api.pageSize).isEqualTo(100)
        assertThat(props.retryDelays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
    }
}
