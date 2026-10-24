package com.narareminder.job

import org.springframework.data.jpa.repository.JpaRepository

interface JobStateRepository : JpaRepository<JobState, JobName>
