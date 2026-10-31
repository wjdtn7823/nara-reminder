package com.narareminder.admin

import com.narareminder.job.JobName
import com.narareminder.job.JobRun
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class JobRunResponse(
    val id: Long, val jobName: JobName, val startedAt: Instant, val finishedAt: Instant,
    val windowFrom: Instant, val windowTo: Instant, val fetchedCount: Int, val matchedCount: Int,
    val sentCount: Int, val status: JobRunStatus, val error: String?,
)

data class RunPageResponse(
    val items: List<JobRunResponse>, val page: Int, val size: Int, val totalPages: Int, val totalElements: Long,
)

@RestController
@RequestMapping("/api/admin/runs")
class JobRunController(private val repository: JobRunRepository) {

    @GetMapping
    fun list(
        @RequestParam(required = false) job: JobName?,
        @RequestParam(required = false) status: JobRunStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): RunPageResponse {
        val spec = listOfNotNull(
            job?.let { value -> Specification<JobRun> { root, _, cb -> cb.equal(root.get<JobName>("jobName"), value) } },
            status?.let { value -> Specification<JobRun> { root, _, cb -> cb.equal(root.get<JobRunStatus>("status"), value) } },
        ).fold(Specification.unrestricted<JobRun>()) { acc, s -> acc.and(s) }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_SIZE), Sort.by(Sort.Direction.DESC, "startedAt"))
        val result = repository.findAll(spec, pageable)
        return RunPageResponse(
            items = result.content.map { it.toResponse() },
            page = result.number, size = result.size, totalPages = result.totalPages, totalElements = result.totalElements,
        )
    }

    private fun JobRun.toResponse() = JobRunResponse(
        id = requireNotNull(id), jobName = jobName, startedAt = startedAt, finishedAt = finishedAt,
        windowFrom = windowFrom, windowTo = windowTo, fetchedCount = fetchedCount, matchedCount = matchedCount,
        sentCount = sentCount, status = status, error = error,
    )

    companion object {
        private const val MAX_SIZE = 100
    }
}
