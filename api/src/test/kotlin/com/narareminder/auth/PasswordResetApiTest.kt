package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.support.withXsrf
import com.narareminder.support.xsrfCookie
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class PasswordResetApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val outbox: OutboxMailRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val em: EntityManager,
) {
    private fun post(path: String, json: String): ResultActionsDsl {
        val xsrf = mockMvc.xsrfCookie()
        return mockMvc.post(path) {
            withXsrf(xsrf)
            contentType = MediaType.APPLICATION_JSON
            content = json
        }
    }

    private fun forgot(email: String) = post("/api/auth/forgot", """{"email":"$email"}""")
    private fun reset(token: String, password: String) = post("/api/auth/reset", """{"token":"$token","password":"$password"}""")
    private fun login(email: String, password: String) = post("/api/auth/login", """{"email":"$email","password":"$password"}""")

    private fun lastTokenFor(email: String): String {
        val html = outbox.findAll().filter { it.toAddress == email }.maxBy { requireNotNull(it.id) }.html
        return requireNotNull(Regex("token=([A-Za-z0-9_-]+)").find(html)).groupValues[1]
    }

    @Test
    fun `없는 이메일이어도 같은 응답을 주고 메일은 넣지 않는다`() {
        val before = outbox.count()

        forgot("nobody@example.com").andExpect { status { isAccepted() } }

        assertThat(outbox.count()).isEqualTo(before)
    }

    @Test
    fun `재설정 링크로 비밀번호를 바꾸면 잠금도 풀리고 새 비밀번호로 로그인된다`() {
        val user = accountService.create("member@example.com", passwordEncoder.encode("old-password"), admin = false)
        user.lockedUntil = Instant.now().plusSeconds(600)
        users.saveAndFlush(user)

        forgot("Member@Example.com").andExpect { status { isAccepted() } }
        val token = lastTokenFor("member@example.com")
        assertThat(outbox.findAll().maxBy { requireNotNull(it.id) }.html).contains("http://localhost:5173/reset?token=")
        reset(token, "new-password").andExpect { status { isNoContent() } }

        login("member@example.com", "new-password").andExpect { status { isOk() } }
        login("member@example.com", "old-password").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `비밀번호 없는 관리자 계정도 재설정으로 첫 비밀번호를 정한다`() {
        accountService.create("boss@example.com", null, admin = true)

        forgot("boss@example.com")
        reset(lastTokenFor("boss@example.com"), "first-password").andExpect { status { isNoContent() } }

        login("boss@example.com", "first-password").andExpect {
            status { isOk() }
            jsonPath("$.admin") { value(true) }
        }
    }

    @Test
    fun `재설정 링크는 한 번만 쓸 수 있고 30분이 지나면 400`() {
        accountService.create("member@example.com", passwordEncoder.encode("old-password"), admin = false)
        forgot("member@example.com")
        val token = lastTokenFor("member@example.com")
        reset(token, "new-password").andExpect { status { isNoContent() } }
        reset(token, "other-password").andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }

        em.flush()
        jdbc.update("UPDATE email_token SET created_at = now() - interval '2 minutes'")
        em.clear()
        forgot("member@example.com")
        val late = lastTokenFor("member@example.com")
        em.flush()
        jdbc.update("UPDATE email_token SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL")
        em.clear()
        reset(late, "new-password").andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }
    }

    @Test
    fun `60초 안에 다시 요청하면 메일을 또 만들지 않고 응답은 같다`() {
        accountService.create("member@example.com", passwordEncoder.encode("old-password"), admin = false)
        val before = outbox.count()

        forgot("member@example.com").andExpect { status { isAccepted() } }
        forgot("member@example.com").andExpect { status { isAccepted() } }

        assertThat(outbox.count()).isEqualTo(before + 1)
    }
}
