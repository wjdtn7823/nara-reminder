package com.narareminder.support

import com.narareminder.security.AppPrincipal
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.RequestPostProcessor

fun authAs(userId: Long, email: String = "user$userId@example.com", admin: Boolean = false): RequestPostProcessor =
    authentication(AppPrincipal(userId, email, admin).toAuthentication())
