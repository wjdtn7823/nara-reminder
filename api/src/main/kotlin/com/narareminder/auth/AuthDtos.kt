package com.narareminder.auth

data class LoginRequest(val email: String, val password: String)

data class MeResponse(val email: String, val admin: Boolean)
