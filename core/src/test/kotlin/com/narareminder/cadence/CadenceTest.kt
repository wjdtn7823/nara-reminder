package com.narareminder.cadence

import com.narareminder.support.KST
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class CadenceTest {

    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    // 2026-10-30 은 금요일
    private val now = kst("2026-10-30T14:03:00")

    @Test
    fun `5분 주기는 통과하고 앞뒤 공백을 지운다`() {
        assertThat(Cadence.validate("  0 */5 * * * *  ", now)).isEqualTo("0 */5 * * * *")
    }

    @Test
    fun `5분보다 촘촘한 주기는 거절한다`() {
        assertThatThrownBy { Cadence.validate("0 * * * * *", now) }
            .isInstanceOf(InvalidCadenceException::class.java)
            .hasMessageContaining("5분")
    }

    @Test
    fun `하루 중 짧은 간격이 섞여 있어도 거절한다`() {
        assertThatThrownBy { Cadence.validate("0 0,1 9 * * *", now) }.isInstanceOf(InvalidCadenceException::class.java)
    }

    @Test
    fun `형식이 틀리면 거절한다`() {
        assertThatThrownBy { Cadence.validate("매일 아침", now) }
            .isInstanceOf(InvalidCadenceException::class.java)
            .hasMessageContaining("cron")
    }

    @Test
    fun `평일 업무시간 30분 주기는 금요일 저녁 다음에 월요일 9시에 울린다`() {
        assertThat(Cadence.nextTimes("0 */30 9-17 * * MON-FRI", kst("2026-10-30T17:45:00"), 2))
            .containsExactly(kst("2026-11-02T09:00:00"), kst("2026-11-02T09:30:00"))
    }

    @Test
    fun `매칭 뒤 첫 cron 시각부터 발송 대상이다`() {
        val matchedAt = kst("2026-10-30T14:03:10")

        assertThat(Cadence.isDue(listOf(Cadence.IMMEDIATE), matchedAt, kst("2026-10-30T14:04:59"))).isFalse()
        assertThat(Cadence.isDue(listOf(Cadence.IMMEDIATE), matchedAt, kst("2026-10-30T14:05:00"))).isTrue()
    }

    @Test
    fun `여러 항목에 걸리면 가장 빠른 주기를 따른다`() {
        val matchedAt = kst("2026-10-30T14:03:10")
        val crons = listOf("0 0 9 * * *", Cadence.IMMEDIATE)

        assertThat(Cadence.earliestFire(crons, matchedAt)).isEqualTo(kst("2026-10-30T14:05:00"))
        assertThat(Cadence.isDue(listOf("0 0 9 * * *"), matchedAt, kst("2026-10-30T14:05:00"))).isFalse()
    }

    @Test
    fun `판정을 건너뛰어도 나중 판정에서 발송 대상이다`() {
        val matchedAt = kst("2026-10-30T14:03:10")

        assertThat(Cadence.isDue(listOf("0 0 9 * * *"), matchedAt, kst("2026-10-31T15:00:00"))).isTrue()
    }

    @Test
    fun `걸린 항목이 없으면 발송 대상이 아니다`() {
        assertThat(Cadence.isDue(emptyList(), now, now.plusSeconds(3600))).isFalse()
    }
}
