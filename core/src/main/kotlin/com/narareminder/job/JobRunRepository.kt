package com.narareminder.job

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface JobRunRepository : JpaRepository<JobRun, Long>, JpaSpecificationExecutor<JobRun> {
    @Modifying
    @Query("delete from JobRun r where r.startedAt < :cutoff")
    fun deleteByStartedAtBefore(cutoff: Instant): Int

    @Query("select coalesce(sum(r.sentCount), 0) from JobRun r where r.jobName = :job and r.startedAt >= :since")
    fun sumSentCountSince(job: JobName, since: Instant): Long
}
