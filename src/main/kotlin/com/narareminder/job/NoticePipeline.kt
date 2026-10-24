package com.narareminder.job

import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.Notice
import com.narareminder.notice.matches
import com.narareminder.store.NotifiedNotice
import com.narareminder.store.NotifiedNoticeRepository
import com.narareminder.store.notifiedId
import com.narareminder.support.KST
import com.narareminder.support.retrying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

@Component
class NoticePipeline(
    private val client: NaraApiClient,
    private val notifiedNoticeRepository: NotifiedNoticeRepository,
    private val jobStateRepository: JobStateRepository,
    private val mailNotifier: MailNotifier,
    private val props: NaraProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실패하면 lastSuccessAt 을 갱신하지 않아 다음 배치가 같은 구간을 다시 조회한다
    suspend fun run(job: JobName) {
        // JPA·SMTP 블로킹 호출이 WebClient 이벤트 루프 스레드에서 실행되지 않도록 IO 디스패처로 옮긴다
        withContext(Dispatchers.IO) {
            val settings = props.settingsOf(job)
            val now = clock.instant()
            val state = jobStateRepository.findByIdOrNull(job) ?: JobState(job)
            val window = QueryWindow.of(state.lastSuccessAt, now, settings)
            val errors = mutableListOf<String>()

            val fetched = props.filter.categories.flatMap { category ->
                try {
                    client.fetch(job.source, category, window.from.toKst(), window.to.toKst())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${category.label} 조회 실패: ${e.message}"
                    emptyList()
                }
            }

            val newNotices = excludeNotified(fetched.filter { props.filter.matches(it) })
            if (newNotices.isNotEmpty()) {
                try {
                    retrying(props.retryDelays) { mailNotifier.sendNotices(job.source, newNotices, now) }
                    notifiedNoticeRepository.saveAll(newNotices.map { NotifiedNotice(it.notifiedId(), it.title, now) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "메일 발송/기록 실패: ${e.message}"
                }
            }

            if (errors.isEmpty()) {
                state.recordSuccess(now)
            } else {
                state.recordFailure()
                log.error("[{}] 수집 실패 ({}회 연속): {}", job, state.consecutiveFailures, errors)
                alertIfNeeded(job, state, settings, errors)
            }
            jobStateRepository.save(state)
        }
    }

    private fun excludeNotified(notices: List<Notice>): List<Notice> {
        val unique = notices.distinctBy { it.notifiedId() }
        val notified = notifiedNoticeRepository.findAllById(unique.map { it.notifiedId() }).map { it.id }.toSet()
        return unique.filterNot { it.notifiedId() in notified }
    }

    private fun alertIfNeeded(job: JobName, state: JobState, settings: NaraProperties.Job, errors: List<String>) {
        if (!state.shouldAlert(settings.alertAfterFailures)) return
        try {
            mailNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, errors.joinToString("\n"))
            state.markAlertSent()
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 메일 발송 실패", job, e)
        }
    }

    private fun NaraProperties.settingsOf(job: JobName): NaraProperties.Job = when (job) {
        JobName.BID_POLLING -> bid
        JobName.PRE_SPEC_DAILY -> preSpec
    }

    private fun Instant.toKst(): LocalDateTime = atZone(KST).toLocalDateTime()
}
