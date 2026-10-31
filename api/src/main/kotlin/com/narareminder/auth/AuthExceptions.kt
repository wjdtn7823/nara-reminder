package com.narareminder.auth

import com.narareminder.web.ApiException
import org.springframework.http.HttpStatus

class InvalidCredentialsException :
    ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다")

class AccountLockedException :
    ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "로그인을 5번 실패해 15분 동안 잠겼습니다. 잠시 후 다시 시도하거나 비밀번호를 재설정하세요")

class InvalidInviteException :
    ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITE", "초대 코드가 올바르지 않거나 만료되었습니다")

class AlreadyRegisteredException :
    ApiException(HttpStatus.CONFLICT, "ALREADY_REGISTERED", "이미 가입된 이메일입니다")

class InvalidTokenException :
    ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "링크가 만료되었거나 이미 사용되었습니다")
