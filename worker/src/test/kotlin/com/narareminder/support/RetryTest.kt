package com.narareminder.support

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class RetryTest {

    private val delays = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))

    @Test
    fun `실패하다 성공하면 결과를 돌려준다`() = runTest {
        var attempts = 0
        val result = retrying(delays) {
            attempts++
            if (attempts < 3) error("일시 오류")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `재시도를 모두 소진하면 마지막 예외를 던진다`() {
        var attempts = 0

        assertThatThrownBy {
            runBlocking {
                retrying(delays.map { Duration.ZERO }) {
                    attempts++
                    error("계속 실패 $attempts")
                }
            }
        }.hasMessage("계속 실패 4")
        assertThat(attempts).isEqualTo(4)
    }

    @Test
    fun `재시도 대상이 아니면 즉시 던진다`() {
        var attempts = 0

        assertThatThrownBy {
            runBlocking {
                retrying(delays, retryOn = { false }) {
                    attempts++
                    error("영구 오류")
                }
            }
        }.hasMessage("영구 오류")
        assertThat(attempts).isEqualTo(1)
    }
}
