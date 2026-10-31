package com.narareminder.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// 링크에는 원래 토큰을, DB 에는 해시만 둔다
object TokenCodec {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
