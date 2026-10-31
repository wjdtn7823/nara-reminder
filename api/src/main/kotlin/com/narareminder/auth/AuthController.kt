package com.narareminder.auth

import com.narareminder.security.AppPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class AuthController(
    private val authService: AuthService,
    private val securityContextRepository: SecurityContextRepository,
    private val signupService: SignupService,
    private val passwordResetService: PasswordResetService,
) {
    @PostMapping("/auth/login")
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): MeResponse {
        val principal = authService.authenticate(body.email, body.password)
        // 세션 고정 공격을 막기 위해 로그인 때 세션 ID 를 바꾼다
        request.getSession(true)
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = principal.toAuthentication() }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        return MeResponse(principal.email, principal.admin)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AppPrincipal): MeResponse = MeResponse(principal.email, principal.admin)

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun signup(@RequestBody body: SignupRequest) {
        signupService.requestSignup(body.inviteCode, body.email, body.password)
    }

    @PostMapping("/auth/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verify(@RequestBody body: TokenRequest) {
        signupService.verify(body.token)
    }

    @PostMapping("/auth/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgot(@RequestBody body: ForgotRequest) {
        passwordResetService.requestReset(body.email)
    }

    @PostMapping("/auth/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@RequestBody body: ResetRequest) {
        passwordResetService.reset(body.token, body.password)
    }
}
