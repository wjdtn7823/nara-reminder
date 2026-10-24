package com.narareminder

import com.narareminder.support.KST
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class NaraReminderApplication {

    @Bean
    fun clock(): Clock = Clock.system(KST)
}

fun main(args: Array<String>) {
    runApplication<NaraReminderApplication>(*args)
}
