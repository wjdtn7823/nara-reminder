package com.narareminder.security

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.io.Serializable

// 세션에 저장되므로 Serializable 이어야 한다
data class AppPrincipal(val userId: Long, val email: String, val admin: Boolean) : Serializable {

    fun authorities(): List<GrantedAuthority> =
        listOfNotNull(SimpleGrantedAuthority("ROLE_USER"), SimpleGrantedAuthority("ROLE_ADMIN").takeIf { admin })

    fun toAuthentication(): Authentication = UsernamePasswordAuthenticationToken.authenticated(this, null, authorities())
}
