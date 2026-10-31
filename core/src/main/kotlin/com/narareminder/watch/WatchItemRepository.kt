package com.narareminder.watch

import org.springframework.data.jpa.repository.JpaRepository

interface WatchItemRepository : JpaRepository<WatchItem, Long> {
    fun findAllByUserIdOrderByIdAsc(userId: Long): List<WatchItem>
    fun findAllByUserIdIn(userIds: Collection<Long>): List<WatchItem>
    fun findByIdAndUserId(id: Long, userId: Long): WatchItem?
    fun existsByUserIdAndTypeAndValueIgnoreCase(userId: Long, type: WatchItemType, value: String): Boolean
    fun existsByUserIdAndTypeAndValueIgnoreCaseAndIdNot(userId: Long, type: WatchItemType, value: String, id: Long): Boolean
}
