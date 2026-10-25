package com.narareminder.job

import com.narareminder.alert.AlertMarkers
import com.narareminder.alert.DiscordNotifier
import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.filter.FilterService
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
    private val discordNotifier: DiscordNotifier,
    private val filterService: FilterService,
    private val jobRunRepository: JobRunRepository,
    private val props: NaraProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실패하면 lastSuccessAt 을 갱신하지 않아 다음 배치가 같은 구간을 다시 조회한다
    suspend fun run(job: JobName) {
        // JPA·SMTP 블로킹 호출이 WebClient 이벤트 루프 스레드에서 실행되지 않도록 IO 디스패처로 옮긴다
        withContext(Dispatchers.IO) {
            val filter = filterService.current()
            val settings = props.settingsOf(job)
            val now = clock.instant()
            val state = jobStateRepository.findByIdOrNull(job) ?: JobState(job)
            val window = QueryWindow.of(state.lastSuccessAt, now, settings)
            val errors = mutableListOf<String>()

            val fetched = filter.categories.flatMap { category ->
                try {
                    client.fetch(job.source, category, window.from.toKst(), window.to.toKst())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${category.label} 조회 실패: ${e.message ?: e.javaClass.simpleName}"
                    emptyList()
                }
            }

            val newNotices = try {
                excludeNotified(fetched.filter { filter.matches(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "발송 이력 조회 실패: ${e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
            var sentCount = 0
            if (newNotices.isNotEmpty()) {
                try {
                    retrying(props.retryDelays) { mailNotifier.sendNotices(job.source, newNotices, now) }
                    notifiedNoticeRepository.saveAll(newNotices.map { NotifiedNotice(it.notifiedId(), it.title, now) })
                    sentCount = newNotices.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "메일 발송/기록 실패: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            if (errors.isEmpty()) {
                state.recordSuccess(now)
            } else {
                state.recordFailure()
                log.error(AlertMarkers.HANDLED_BY_JOB_ALERT, "[{}] 수집 실패 ({}회 연속): {}", job, state.consecutiveFailures, errors)
                alertIfNeeded(job, state, settings, errors)
            }
            recordRun(job, now, window, fetched.size, newNotices.size, sentCount, errors)
            try {
                jobStateRepository.save(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("[{}] 작업 상태 저장 실패", job, e)
                throw e
            }
        }
    }

    // 실행 이력은 조회용 부가 정보라 저장에 실패해도 수집 결과에는 영향을 주지 않는다
    private fun recordRun(
        job: JobName, startedAt: Instant, window: QueryWindow,
        fetchedCount: Int, matchedCount: Int, sentCount: Int, errors: List<String>,
    ) {
        try {
            jobRunRepository.save(
                JobRun(
                    jobName = job, startedAt = startedAt, finishedAt = clock.instant(),
                    windowFrom = window.from, windowTo = window.to,
                    fetchedCount = fetchedCount, matchedCount = matchedCount, sentCount = sentCount,
                    status = if (errors.isEmpty()) JobRunStatus.SUCCESS else JobRunStatus.FAILURE,
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ),
            )
        } catch (e: Exception) {
            log.error("[{}] 실행 이력 저장 실패", job, e)
        }
    }

    private fun excludeNotified(notices: List<Notice>): List<Notice> {
        val unique = notices.distinctBy { it.notifiedId() }
        val notified = notifiedNoticeRepository.findAllById(unique.map { it.notifiedId() }).map { it.id }.toSet()
        return unique.filterNot { it.notifiedId() in notified }
    }

    private fun alertIfNeeded(job: JobName, state: JobState, settings: NaraProperties.Job, errors: List<String>) {
        if (!state.shouldAlert(settings.alertAfterFailures)) return
        val message = errors.joinToString("\n")
        val mailSent = try {
            mailNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, message)
            true
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 메일 발송 실패", job, e)
            false
        }
        val discordSent = try {
            discordNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, message)
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 디스코드 발송 실패", job, e)
            false
        }
        // 한 채널이라도 전달됐으면 같은 장애로 다시 알리지 않는다
        if (mailSent || discordSent) state.markAlertSent()
    }

    private fun NaraProperties.settingsOf(job: JobName): NaraProperties.Job = when (job) {
        JobName.BID_POLLING -> bid
        JobName.PRE_SPEC_DAILY -> preSpec
    }

    private fun Instant.toKst(): LocalDateTime = atZone(KST).toLocalDateTime()
}
