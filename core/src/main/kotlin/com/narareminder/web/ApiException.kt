package com.narareminder.web

import org.springframework.http.HttpStatus

open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)

class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)
