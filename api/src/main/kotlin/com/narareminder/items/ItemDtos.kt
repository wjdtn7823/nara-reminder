package com.narareminder.items

import com.narareminder.cadence.Cadence
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.notice.NoticeSource
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemType
import java.time.Instant
import java.time.LocalDateTime

data class ItemResponse(
    val id: Long, val type: WatchItemType, val value: String, val cron: String, val nextSendAt: Instant?, val createdAt: Instant,
)

data class ItemStats(val matched: Int, val sent: Int, val pending: Int)

data class RecentNotice(
    val id: Long, val status: DeliveryStatus, val matchedAt: Instant, val sentAt: Instant?, val scheduledAt: Instant?,
    val source: NoticeSource, val noticeNo: String, val title: String, val agency: String?, val price: Long?,
    val deadlineAt: LocalDateTime?, val detailUrl: String?, val revision: Boolean,
)

data class ItemDetailResponse(val item: ItemResponse, val stats: ItemStats, val recent: List<RecentNotice>)

data class CreateItemRequest(val type: WatchItemType, val value: String, val cron: String = Cadence.IMMEDIATE)

data class UpdateItemRequest(val value: String? = null, val cron: String? = null)

fun WatchItem.toResponse(now: Instant) = ItemResponse(
    id = requireNotNull(id), type = type, value = value, cron = cron, nextSendAt = Cadence.nextFire(cron, now), createdAt = createdAt,
)
