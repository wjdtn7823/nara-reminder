package com.narareminder.auth

data class LoginRequest(val email: String, val password: String)

data class MeResponse(val email: String, val admin: Boolean)

data class SignupRequest(val inviteCode: String, val email: String, val password: String)

data class TokenRequest(val token: String)

data class ForgotRequest(val email: String)

data class ResetRequest(val token: String, val password: String)
