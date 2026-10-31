package com.narareminder.job

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NoticeJobs(private val pipeline: NoticePipeline) {

    @Scheduled(fixedDelayString = "\${nara.bid.poll-interval}", initialDelayString = "PT10S")
    suspend fun pollBids() {
        pipeline.run(JobName.BID_POLLING)
    }

    @Scheduled(cron = "\${nara.pre-spec.cron}", zone = "Asia/Seoul")
    suspend fun collectPreSpecs() {
        pipeline.run(JobName.PRE_SPEC_DAILY)
    }
}
