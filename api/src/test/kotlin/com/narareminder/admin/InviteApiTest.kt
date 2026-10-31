package com.narareminder.admin

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.support.authAs
import com.narareminder.support.withXsrf
import com.narareminder.support.xsrfCookie
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class InviteApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
) {
    private val adminId by lazy { requireNotNull(accountService.create("admin@example.com", "hash", admin = true).id) }

    private fun create(json: String, admin: Boolean = true) = mockMvc.post("/api/admin/invites") {
        with(authAs(adminId, "admin@example.com", admin))
        withXsrf(mockMvc.xsrfCookie())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    @Test
    fun `초대 코드를 만들면 사용 가능 상태로 목록 맨 위에 나온다`() {
        create("""{"maxUses":5,"validDays":7}""").andExpect {
            status { isCreated() }
            jsonPath("$.code") { value(matchesPattern("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$")) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.maxUses") { value(5) }
            jsonPath("$.usedCount") { value(0) }
        }
        create("""{"maxUses":1,"validDays":1}""")

        mockMvc.get("/api/admin/invites") { with(authAs(adminId, admin = true)) }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].maxUses") { value(1) }
        }
    }

    @Test
    fun `회수하면 REVOKED 가 된다`() {
        val body = create("""{"maxUses":5,"validDays":7}""").andReturn().response.contentAsString
        val id = requireNotNull(Regex("\"id\":(\\d+)").find(body)).groupValues[1]

        mockMvc.post("/api/admin/invites/$id/revoke") {
            with(authAs(adminId, admin = true))
            withXsrf(mockMvc.xsrfCookie())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REVOKED") }
        }
    }

    @Test
    fun `사용 한도·유효 기간이 범위를 벗어나면 400`() {
        create("""{"maxUses":0,"validDays":7}""").andExpect { jsonPath("$.code") { value("INVALID_INVITE_OPTION") } }
        create("""{"maxUses":5,"validDays":31}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `관리자가 아니면 403`() {
        create("""{"maxUses":5,"validDays":7}""", admin = false).andExpect { status { isForbidden() } }
    }
}
