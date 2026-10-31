package com.narareminder.items

import com.narareminder.web.BadRequestException

// 제외 키워드는 줄바꿈으로 구분해 저장하므로 값에 줄바꿈을 허용하지 않는다
fun normalizeValue(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty() || value.length > MAX_VALUE_LENGTH || value.contains('\n')) {
        throw BadRequestException("INVALID_VALUE", "값은 1~${MAX_VALUE_LENGTH}자여야 합니다")
    }
    return value
}

private const val MAX_VALUE_LENGTH = 100
