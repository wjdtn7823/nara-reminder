package com.narareminder.job

import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.matching.UserProfile
import com.narareminder.notice.Notice
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.key
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItemRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class NoticeCollector(
    private val noticeRepository: StoredNoticeRepository,
    private val userRepository: AppUserRepository,
    private val watchItemRepository: WatchItemRepository,
    private val userFilterRepository: UserFilterRepository,
    private val deliveryRepository: DeliveryRepository,
    private val deliveryItemRepository: DeliveryItemRepository,
) {
    data class Result(val newCount: Int, val deliveryCount: Int)

    // 공고 저장과 매칭을 한 트랜잭션으로 묶어, 실패하면 둘 다 없던 일이 되고 다음 실행이 다시 처리한다
    @Transactional
    fun storeAndMatch(notices: List<Notice>, now: Instant): Result {
        val unique = notices.distinctBy { it.key() }
        val existing = noticeRepository.findAllById(unique.map { it.key() }).map { it.id }.toSet()
        val fresh = unique.filterNot { it.key() in existing }
        if (fresh.isEmpty()) return Result(0, 0)

        noticeRepository.saveAll(fresh.map { StoredNotice.of(it, now) })
        val profiles = loadProfiles()
        var deliveryCount = 0
        for (notice in fresh) {
            for (profile in profiles) {
                val matched = profile.match(notice)
                if (matched.isEmpty()) continue
                val delivery = deliveryRepository.save(Delivery(profile.userId, notice.key(), now))
                deliveryItemRepository.saveAll(
                    matched.map { DeliveryItem(DeliveryItemId(delivery.requireId(), requireNotNull(it.id))) },
                )
                deliveryCount++
            }
        }
        return Result(fresh.size, deliveryCount)
    }

    private fun loadProfiles(): List<UserProfile> {
        val userIds = userRepository.findAllByStatus(UserStatus.ACTIVE).map { requireNotNull(it.id) }
        val itemsByUser = watchItemRepository.findAllByUserIdIn(userIds).groupBy { it.userId }
        val filters = userFilterRepository.findAllById(userIds).associateBy { it.userId }
        return userIds.mapNotNull { userId ->
            val items = itemsByUser[userId].orEmpty()
            val filter = filters[userId]
            if (items.isEmpty() || filter == null) null else UserProfile(userId, filter, items)
        }
    }
}
