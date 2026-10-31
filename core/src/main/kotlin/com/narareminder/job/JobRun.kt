package com.narareminder.job

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class JobRunStatus { SUCCESS, FAILURE }

@Entity
@Table(name = "job_run")
class JobRun(
    @Enumerated(EnumType.STRING)
    val jobName: JobName,
    val startedAt: Instant,
    val finishedAt: Instant,
    val windowFrom: Instant,
    val windowTo: Instant,
    val fetchedCount: Int,
    val matchedCount: Int,
    val sentCount: Int,
    @Enumerated(EnumType.STRING)
    val status: JobRunStatus,
    @Column(columnDefinition = "text")
    val error: String?,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
