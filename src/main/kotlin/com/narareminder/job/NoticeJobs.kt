package com.narareminder.job

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NoticeJobs(private val pipeline: NoticePipeline) {

    @Scheduled(cron = "\${nara.bid.cron}", zone = "Asia/Seoul")
    suspend fun pollBids() {
        pipeline.run(JobName.BID_POLLING)
    }

    @Scheduled(cron = "\${nara.pre-spec.cron}", zone = "Asia/Seoul")
    suspend fun collectPreSpecs() {
        pipeline.run(JobName.PRE_SPEC_DAILY)
    }
}
