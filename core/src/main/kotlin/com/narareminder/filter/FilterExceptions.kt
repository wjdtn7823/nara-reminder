package com.narareminder.filter

import com.narareminder.web.ApiException
import org.springframework.http.HttpStatus

class InvalidFilterException(message: String) : ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", message)

class DuplicateFilterTermException(type: FilterTermType, value: String) :
    ApiException(HttpStatus.CONFLICT, "DUPLICATE", "이미 등록된 ${type.label}입니다: $value")
