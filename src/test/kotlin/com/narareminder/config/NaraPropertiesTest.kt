package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class NaraPropertiesTest {

    private val required = mapOf(
        "nara.service-key" to "key",
        "nara.mail.from" to "bot@example.com",
        "nara.mail.to" to "a@example.com,b@example.com",
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

        assertThat(props.mail.to).containsExactly("a@example.com", "b@example.com")
        assertThat(props.bid.overlap).isEqualTo(Duration.ofMinutes(30))
        assertThat(props.bid.maxLookback).isEqualTo(Duration.ofDays(7))
        assertThat(props.preSpec.alertAfterFailures).isEqualTo(1)
        assertThat(props.api.pageSize).isEqualTo(100)
        assertThat(props.retryDelays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
    }

    @Test
    fun `필터 기본값은 전체 업무구분과 빈 조건이다`() {
        val filter = bind().filter

        assertThat(filter.categories).containsExactlyElementsOf(NoticeCategory.entries)
        assertThat(filter.keywords).isEmpty()
        assertThat(filter.priceMin).isNull()
    }

    @Test
    fun `필터 조건을 바인딩한다`() {
        val filter = bind(
            "nara.filter.keywords" to "AI,유지보수",
            "nara.filter.categories" to "SERVICE,GOODS",
            "nara.filter.price-min" to "50000000",
        ).filter

        assertThat(filter.keywords).containsExactly("AI", "유지보수")
        assertThat(filter.categories).containsExactly(NoticeCategory.SERVICE, NoticeCategory.GOODS)
        assertThat(filter.priceMin).isEqualTo(50_000_000L)
    }
}
