package com.narareminder.cron

import com.narareminder.PostgresTestConfig
import com.narareminder.support.authAs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class CronApiTest @Autowired constructor(private val mockMvc: MockMvc) {

    @Test
    fun `유효한 cron 은 다음 발송 3회를 돌려준다`() {
        mockMvc.get("/api/cron/preview") {
            with(authAs(1))
            param("expr", "0 */30 9-17 * * MON-FRI")
        }.andExpect {
            status { isOk() }
            jsonPath("$.cron") { value("0 */30 9-17 * * MON-FRI") }
            jsonPath("$.next.length()") { value(3) }
        }
    }

    @Test
    fun `틀리거나 촘촘한 cron 은 400 INVALID_CRON`() {
        mockMvc.get("/api/cron/preview") {
            with(authAs(1))
            param("expr", "0 * * * * *")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CRON") }
        }
    }

    @Test
    fun `로그인하지 않으면 401`() {
        mockMvc.get("/api/cron/preview") { param("expr", "0 */5 * * * *") }.andExpect { status { isUnauthorized() } }
    }
}
