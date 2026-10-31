package com.narareminder.settings

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.support.authAs
import com.narareminder.support.withXsrf
import com.narareminder.support.xsrfCookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class SettingsApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
) {
    private val me by lazy { requireNotNull(accountService.create("me@example.com", "hash", admin = false).id) }

    private fun save(json: String) = mockMvc.put("/api/settings") {
        with(authAs(me))
        withXsrf(mockMvc.xsrfCookie())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    @Test
    fun `새 사용자의 공통 조건은 제외 없음·전체 업무구분·가격 제한 없음이다`() {
        mockMvc.get("/api/settings") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.excludeKeywords.length()") { value(0) }
            jsonPath("$.categories.length()") { value(4) }
            jsonPath("$.priceMin") { value(null) }
        }
    }

    @Test
    fun `저장하면 제외 키워드를 다듬고 대소문자 중복을 없앤다`() {
        save("""{"excludeKeywords":[" 교육 ","청소","교육"],"categories":["SERVICE"],"priceMin":1000,"priceMax":null}""").andExpect {
            status { isOk() }
            jsonPath("$.excludeKeywords.length()") { value(2) }
            jsonPath("$.excludeKeywords[0]") { value("교육") }
            jsonPath("$.categories[0]") { value("SERVICE") }
            jsonPath("$.priceMin") { value(1000) }
        }
        mockMvc.get("/api/settings") { with(authAs(me)) }.andExpect { jsonPath("$.excludeKeywords[1]") { value("청소") } }
    }

    @Test
    fun `업무구분이 없거나 가격 범위가 뒤집히면 400`() {
        save("""{"excludeKeywords":[],"categories":[],"priceMin":null,"priceMax":null}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_SETTINGS") }
        }
        save("""{"excludeKeywords":[],"categories":["GOODS"],"priceMin":2000,"priceMax":1000}""").andExpect { status { isBadRequest() } }
        save("""{"excludeKeywords":[],"categories":["GOODS"],"priceMin":-1,"priceMax":null}""").andExpect { status { isBadRequest() } }
    }
}
