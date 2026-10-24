package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import java.time.Duration

fun testProperties(
    baseUrl: String = "http://localhost",
    serviceKey: String = "test-key",
    pageSize: Int = 100,
    filter: NaraProperties.Filter = NaraProperties.Filter(categories = listOf(NoticeCategory.SERVICE)),
    bidAlertAfterFailures: Int = 6,
) = NaraProperties(
    serviceKey = serviceKey,
    mail = NaraProperties.Mail(from = "bot@example.com", to = listOf("me@example.com")),
    bid = NaraProperties.Job(
        overlap = Duration.ofMinutes(30),
        initialLookback = Duration.ofHours(1),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = bidAlertAfterFailures,
    ),
    preSpec = NaraProperties.Job(
        overlap = Duration.ofHours(1),
        initialLookback = Duration.ofHours(24),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = 1,
    ),
    api = NaraProperties.Api(baseUrl = baseUrl, pageSize = pageSize, timeout = Duration.ofSeconds(2)),
    filter = filter,
    retryDelays = listOf(Duration.ZERO, Duration.ZERO, Duration.ZERO),
)
