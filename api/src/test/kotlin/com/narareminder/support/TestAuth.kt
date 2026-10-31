package com.narareminder.support

import com.narareminder.security.AppPrincipal
import jakarta.servlet.http.Cookie
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor

fun authAs(userId: Long, email: String = "user$userId@example.com", admin: Boolean = false): RequestPostProcessor =
    authentication(AppPrincipal(userId, email, admin).toAuthentication())

// 운영과 같은 경로: GET 으로 XSRF-TOKEN 쿠키를 받아 쿠키와 X-XSRF-TOKEN 헤더로 돌려보낸다
fun MockMvc.xsrfCookie(): Cookie =
    requireNotNull(get("/api/me").andReturn().response.getCookie("XSRF-TOKEN"))

fun MockHttpServletRequestDsl.withXsrf(cookie: Cookie) {
    cookie(cookie)
    header("X-XSRF-TOKEN", cookie.value)
}
