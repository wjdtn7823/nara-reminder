package com.narareminder.settings

import com.narareminder.items.normalizeValue
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.web.BadRequestException
import com.narareminder.web.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SettingsService(private val repository: UserFilterRepository) {

    @Transactional(readOnly = true)
    fun get(userId: Long): SettingsResponse = load(userId).toResponse()

    @Transactional
    fun update(userId: Long, request: SettingsRequest): SettingsResponse {
        val (min, max) = request.priceMin to request.priceMax
        if (request.categories.isEmpty()) throw invalid("업무구분을 하나 이상 선택하세요")
        if ((min ?: 0) < 0 || (max ?: 0) < 0) throw invalid("가격은 0 이상이어야 합니다")
        if (min != null && max != null && min > max) throw invalid("최소 가격이 최대 가격보다 클 수 없습니다")
        val keywords = request.excludeKeywords.map { normalizeValue(it) }.distinctBy { it.lowercase() }
        return load(userId).apply { update(keywords, request.categories.distinct(), min, max) }.toResponse()
    }

    private fun load(userId: Long): UserFilter =
        repository.findByIdOrNull(userId) ?: throw NotFoundException("공통 조건이 없습니다: $userId")

    private fun invalid(message: String) = BadRequestException("INVALID_SETTINGS", message)
}
