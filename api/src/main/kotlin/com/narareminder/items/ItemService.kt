package com.narareminder.items

import com.narareminder.cadence.Cadence
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import com.narareminder.web.ConflictException
import com.narareminder.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class ItemService(
    private val itemRepository: WatchItemRepository,
    private val queries: ItemQueries,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<ItemResponse> {
        val now = clock.instant()
        return itemRepository.findAllByUserIdOrderByIdAsc(userId).map { it.toResponse(now) }
    }

    @Transactional
    fun create(userId: Long, request: CreateItemRequest): ItemResponse {
        val now = clock.instant()
        val value = normalizeValue(request.value)
        val cron = Cadence.validate(request.cron, now)
        if (itemRepository.existsByUserIdAndTypeAndValueIgnoreCase(userId, request.type, value)) throw duplicate(request.type, value)
        return itemRepository.save(WatchItem(userId, request.type, value, cron, now)).toResponse(now)
    }

    @Transactional(readOnly = true)
    fun detail(userId: Long, id: Long): ItemDetailResponse {
        val now = clock.instant()
        val item = find(userId, id)
        return ItemDetailResponse(item.toResponse(now), queries.stats(id, now.minus(STATS_PERIOD)), queries.recent(id))
    }

    @Transactional
    fun update(userId: Long, id: Long, request: UpdateItemRequest): ItemResponse {
        val now = clock.instant()
        val item = find(userId, id)
        request.value?.let { raw ->
            val value = normalizeValue(raw)
            if (itemRepository.existsByUserIdAndTypeAndValueIgnoreCaseAndIdNot(userId, item.type, value, id)) throw duplicate(item.type, value)
            item.value = value
        }
        request.cron?.let { item.cron = Cadence.validate(it, now) }
        return item.toResponse(now)
    }

    @Transactional
    fun delete(userId: Long, id: Long) {
        itemRepository.delete(find(userId, id))
    }

    // 남의 항목도 없는 항목과 똑같이 404 로 응답한다
    private fun find(userId: Long, id: Long): WatchItem =
        itemRepository.findByIdAndUserId(id, userId) ?: throw NotFoundException("항목이 없습니다: $id")

    private fun duplicate(type: WatchItemType, value: String) = ConflictException("DUPLICATE", "이미 등록된 ${type.label}입니다: $value")

    companion object {
        private val STATS_PERIOD: Duration = Duration.ofDays(7)
    }
}
