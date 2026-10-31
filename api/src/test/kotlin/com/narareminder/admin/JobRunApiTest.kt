package com.narareminder.admin

import com.narareminder.PostgresTestConfig
import com.narareminder.job.JobName
import com.narareminder.job.JobRun
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
@WithMockUser(roles = ["ADMIN"])
class JobRunApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: JobRunRepository,
) {
    private val base = Instant.parse("2026-10-25T00:00:00Z")

    private fun run(minutes: Long, job: JobName, status: JobRunStatus) = JobRun(
        jobName = job, startedAt = base.plus(Duration.ofMinutes(minutes)),
        finishedAt = base.plus(Duration.ofMinutes(minutes)).plusSeconds(3),
        windowFrom = base, windowTo = base, fetchedCount = 10, matchedCount = 2, sentCount = 2,
        status = status, error = if (status == JobRunStatus.FAILURE) "용역 조회 실패: HTTP 500" else null,
    )

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
        repository.saveAll(
            listOf(
                run(0, JobName.BID_POLLING, JobRunStatus.SUCCESS),
                run(5, JobName.BID_POLLING, JobRunStatus.FAILURE),
                run(10, JobName.PRE_SPEC_DAILY, JobRunStatus.SUCCESS),
            ),
        )
    }

    @Test
    fun `최신순으로 조회한다`() {
        mockMvc.get("/api/admin/runs").andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(3) }
            jsonPath("$.items[0].jobName") { value("PRE_SPEC_DAILY") }
            jsonPath("$.items[2].startedAt") { value("2026-10-25T00:00:00Z") }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.size") { value(50) }
        }
    }

    @Test
    fun `작업과 상태로 거른다`() {
        mockMvc.get("/api/admin/runs?job=BID_POLLING&status=FAILURE").andExpect {
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].error") { value("용역 조회 실패: HTTP 500") }
        }
    }

    @Test
    fun `페이지 크기는 100을 넘지 않는다`() {
        mockMvc.get("/api/admin/runs?size=500&page=0").andExpect { jsonPath("$.size") { value(100) } }
    }

    @Test
    fun `잘못된 job 값은 400`() {
        mockMvc.get("/api/admin/runs?job=FOO").andExpect { status { isBadRequest() } }
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `관리자가 아니면 403`() {
        mockMvc.get("/api/admin/runs").andExpect { status { isForbidden() } }
    }
}
