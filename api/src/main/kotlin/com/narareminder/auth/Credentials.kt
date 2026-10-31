package com.narareminder.auth

import com.narareminder.web.BadRequestException

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    if (email.length > 254 || !EMAIL.matches(email)) throw BadRequestException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다")
    return email
}

// bcrypt 는 72바이트까지만 쓰므로 글자 수가 아니라 바이트로 상한을 건다
fun validatePassword(password: String) {
    if (password.length < 8 || password.toByteArray(Charsets.UTF_8).size > 72) {
        throw BadRequestException("INVALID_PASSWORD", "비밀번호는 8자 이상, 72바이트 이하여야 합니다")
    }
}
