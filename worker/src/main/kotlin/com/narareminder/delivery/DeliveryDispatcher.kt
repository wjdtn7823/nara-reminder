package com.narareminder.delivery

import com.narareminder.alert.DiscordNotifier
import com.narareminder.cadence.Cadence
import com.narareminder.job.JobName
import com.narareminder.job.JobRun
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import com.narareminder.mail.MailNotifier
import com.narareminder.mail.MailQuota
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.WatchItemRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class DeliveryDispatcher(
    private val deliveryRepository: DeliveryRepository,
    private val deliveryItemRepository: DeliveryItemRepository,
    private val watchItemRepository: WatchItemRepository,
    private val noticeRepository: StoredNoticeRepository,
    private val userRepository: AppUserRepository,
    private val mailNotifier: MailNotifier,
    private val discordNotifier: DiscordNotifier,
    private val jobRunRepository: JobRunRepository,
    private val mailQuota: MailQuota,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    fun dispatch() {
        val now = clock.instant()
        val pending = deliveryRepository.findAllByStatus(DeliveryStatus.PENDING)
        if (pending.isEmpty()) return

        val itemIdsByDelivery = deliveryItemRepository.findAllByIdDeliveryIdIn(pending.map { it.requireId() })
            .groupBy({ it.id.deliveryId }, { it.id.watchItemId })
        val cronByItem = watchItemRepository.findAllById(itemIdsByDelivery.values.flatten().distinct())
            .associate { requireNotNull(it.id) to it.cron }

        // 걸린 항목이 모두 삭제된 공고는 보낼 기준이 없으므로 지운다
        val (orphans, live) = pending.partition { itemIdsByDelivery[it.requireId()].isNullOrEmpty() }
        if (orphans.isNotEmpty()) deliveryRepository.deleteAll(orphans)

        val due = live.filter { delivery ->
            val crons = itemIdsByDelivery.getValue(delivery.requireId()).mapNotNull { cronByItem[it] }
            Cadence.isDue(crons, delivery.matchedAt, now)
        }
        if (due.isNotEmpty()) send(due, now)
    }

    private fun send(due: List<Delivery>, now: Instant) {
        val emails = userRepository.findAllById(due.map { it.userId }.distinct()).associate { requireNotNull(it.id) to it.email }
        val notices = noticeRepository.findAllById(due.map { it.notice }.distinct()).associateBy { it.id }
        val errors = mutableListOf<String>()
        val exhausted = mutableListOf<String>()
        var mailCount = 0
        var noticeCount = 0

        due.groupBy { it.userId }.forEach { (userId, deliveries) ->
            val email = emails[userId] ?: return@forEach
            try {
                mailNotifier.sendDigest(email, deliveries.mapNotNull { notices[it.notice] }, now)
                deliveries.forEach { it.markSent(now) }
                mailCount++
                noticeCount += deliveries.size
            } catch (e: Exception) {
                log.warn("[DELIVERY] {} 메일 발송 실패", email, e)
                errors += "$email 발송 실패: ${e.message ?: e.javaClass.simpleName}"
                if (deliveries.map { it.recordFailure(MAX_ATTEMPTS) }.any { it }) exhausted += email
            }
        }
        deliveryRepository.saveAll(due)
        recordRun(now, noticeCount, mailCount, errors)
        if (exhausted.isNotEmpty()) alertExhausted(exhausted, errors)
        if (mailCount > 0) mailQuota.check(now)
    }

    private fun recordRun(startedAt: Instant, noticeCount: Int, mailCount: Int, errors: List<String>) {
        jobRunRepository.save(
            JobRun(
                jobName = JobName.DELIVERY, startedAt = startedAt, finishedAt = clock.instant(),
                windowFrom = startedAt, windowTo = startedAt,
                fetchedCount = 0, matchedCount = noticeCount, sentCount = mailCount,
                status = if (errors.isEmpty()) JobRunStatus.SUCCESS else JobRunStatus.FAILURE,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
        )
    }

    private fun alertExhausted(emails: List<String>, errors: List<String>) {
        try {
            discordNotifier.send(
                "📭 **[나라장터 알림 서버] 메일 발송 ${MAX_ATTEMPTS}회 실패** ${emails.joinToString()}\n```\n${errors.joinToString("\n")}\n```",
            )
        } catch (e: Exception) {
            log.warn("[DELIVERY] 발송 실패 디스코드 알림 실패", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
