package com.narareminder.auth

import com.narareminder.web.BadRequestException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CredentialsTest {
    @Test
    fun `72바이트 ASCII 비밀번호는 통과한다`() {
        assertThatCode { validatePassword("a".repeat(72)) }.doesNotThrowAnyException()
    }

    @Test
    fun `73바이트 비밀번호는 거절한다`() {
        assertThatThrownBy { validatePassword("a".repeat(73)) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `글자 수는 짧아도 72바이트를 넘는 한글 비밀번호는 거절한다`() {
        assertThatThrownBy { validatePassword("가".repeat(30)) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `8자 미만은 거절한다`() {
        assertThatThrownBy { validatePassword("short12") }.isInstanceOf(BadRequestException::class.java)
    }
}
