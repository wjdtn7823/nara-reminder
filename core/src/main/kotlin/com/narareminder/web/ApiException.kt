package com.narareminder.web

import org.springframework.http.HttpStatus

open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)
