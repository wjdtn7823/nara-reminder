package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("nara")
data class NaraProperties(
    val serviceKey: String,
    val mail: Mail,
    val bid: Job,
    val preSpec: Job,
    val api: Api = Api(),
    val filter: Filter = Filter(),
    val retryDelays: List<Duration> = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
) {
    data class Api(
        val baseUrl: String = "https://apis.data.go.kr/1230000",
        val pageSize: Int = 100,
        val timeout: Duration = Duration.ofSeconds(10),
    )

    data class Mail(
        val from: String,
        val to: List<String>,
    )

    data class Job(
        val overlap: Duration,
        val initialLookback: Duration,
        val maxLookback: Duration,
        val alertAfterFailures: Int,
    )

    data class Filter(
        val keywords: List<String> = emptyList(),
        val excludeKeywords: List<String> = emptyList(),
        val categories: List<NoticeCategory> = NoticeCategory.entries,
        val priceMin: Long? = null,
        val priceMax: Long? = null,
        val agencies: List<String> = emptyList(),
    )
}
