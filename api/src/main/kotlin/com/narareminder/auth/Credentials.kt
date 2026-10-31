package com.narareminder.auth

import com.narareminder.web.BadRequestException

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    if (email.length > 254 || !EMAIL.matches(email)) throw BadRequestException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다")
    return email
}

// bcrypt 는 72바이트까지만 쓰므로 그보다 긴 비밀번호는 받지 않는다
fun validatePassword(password: String) {
    if (password.length !in 8..72) throw BadRequestException("INVALID_PASSWORD", "비밀번호는 8~72자여야 합니다")
}
