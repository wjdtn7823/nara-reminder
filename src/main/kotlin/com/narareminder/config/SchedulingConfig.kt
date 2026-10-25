package com.narareminder.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// 테스트에서 스케줄러가 실제 API를 호출하지 않도록 끌 수 있게 한다
@Configuration
@EnableScheduling
@ConditionalOnProperty("nara.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig
