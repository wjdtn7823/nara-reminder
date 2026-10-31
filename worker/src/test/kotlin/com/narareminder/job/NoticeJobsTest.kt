package com.narareminder.job

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NoticeJobsTest {

    private val pipeline = mockk<NoticePipeline>().also { mock ->
        coEvery { mock.run(any()) } returns Unit
    }
    private val jobs = NoticeJobs(pipeline)

    @Test
    fun `입찰공고 폴링은 BID_POLLING 작업을 실행한다`() = runBlocking<Unit> {
        jobs.pollBids()

        coVerify { pipeline.run(JobName.BID_POLLING) }
    }

    @Test
    fun `사전규격 수집은 PRE_SPEC_DAILY 작업을 실행한다`() = runBlocking<Unit> {
        jobs.collectPreSpecs()

        coVerify { pipeline.run(JobName.PRE_SPEC_DAILY) }
    }
}
