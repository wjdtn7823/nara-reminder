package com.narareminder.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
data class AppProperties(
    val adminEmail: String = "",
    // 인증 메일 링크의 기준 주소
    val baseUrl: String = "http://localhost:5173",
)
