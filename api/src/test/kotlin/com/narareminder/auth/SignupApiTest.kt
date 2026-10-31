package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.support.withXsrf
import com.narareminder.support.xsrfCookie
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.InviteCode
import com.narareminder.user.InviteCodeRepository
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class SignupApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val invites: InviteCodeRepository,
    private val outbox: OutboxMailRepository,
    private val jdbc: JdbcTemplate,
    private val em: EntityManager,
) {
    private val now = Instant.now()
    private val adminId by lazy { requireNotNull(accountService.create("admin@example.com", "hash", admin = true).id) }

    private fun invite(code: String = "ABCD-2345", maxUses: Int = 5, expiresAt: Instant = now.plus(Duration.ofDays(7)), usedCount: Int = 0) =
        invites.save(InviteCode(code, maxUses, expiresAt, adminId, now, usedCount))

    private fun post(path: String, json: String): ResultActionsDsl {
        val xsrf = mockMvc.xsrfCookie()
        return mockMvc.post(path) {
            withXsrf(xsrf)
            contentType = MediaType.APPLICATION_JSON
            content = json
        }
    }

    private fun signup(code: String, email: String, password: String = "correct-horse") =
        post("/api/auth/signup", """{"inviteCode":"$code","email":"$email","password":"$password"}""")

    private fun verify(token: String) = post("/api/auth/verify", """{"token":"$token"}""")

    private fun lastTokenFor(email: String): String {
        val html = outbox.findAll().filter { it.toAddress == email }.maxBy { requireNotNull(it.id) }.html
        return requireNotNull(Regex("token=([A-Za-z0-9_-]+)").find(html)).groupValues[1]
    }

    @Test
    fun `초대 코드로 가입 요청하면 확인 메일을 넣고 아직 계정은 만들지 않는다`() {
        invite()

        signup(" abcd-2345 ", "new@example.com").andExpect { status { isAccepted() } }

        assertThat(users.findByEmail("new@example.com")).isNull()
        val mail = outbox.findAll().maxBy { requireNotNull(it.id) }
        assertThat(mail.toAddress).isEqualTo("new@example.com")
        assertThat(mail.html).contains("http://localhost:5173/verify?token=")
    }

    @Test
    fun `확인 링크를 누르면 계정과 기본 공통 조건이 생기고 소문자 이메일로 로그인된다`() {
        val code = invite()
        signup("ABCD-2345", "New@Example.com").andExpect { status { isAccepted() } }

        verify(lastTokenFor("new@example.com")).andExpect { status { isNoContent() } }

        val user = requireNotNull(users.findByEmail("new@example.com"))
        assertThat(filters.findById(requireNotNull(user.id))).isPresent()
        assertThat(invites.findById(requireNotNull(code.id)).orElseThrow().usedCount).isEqualTo(1)
        post("/api/auth/login", """{"email":"new@example.com","password":"correct-horse"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `만료·회수·한도 소진·없는 초대 코드는 400`() {
        invite("EXPI-RED2", expiresAt = now.minusSeconds(1))
        invite("FULL-USE2", maxUses = 1, usedCount = 1)
        invites.save(InviteCode("REVO-KED2", 5, now.plus(Duration.ofDays(1)), adminId, now, revokedAt = now))

        listOf("EXPI-RED2", "FULL-USE2", "REVO-KED2", "NONE-XXXX").forEach { code ->
            signup(code, "new@example.com").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_INVITE") }
            }
        }
    }

    @Test
    fun `비밀번호가 8자 미만이거나 이메일 형식이 틀리면 400`() {
        invite()

        signup("ABCD-2345", "new@example.com", password = "short").andExpect { jsonPath("$.code") { value("INVALID_PASSWORD") } }
        signup("ABCD-2345", "not-an-email").andExpect { jsonPath("$.code") { value("INVALID_EMAIL") } }
    }

    @Test
    fun `미리 만든 계정은 같은 이메일로 가입해 기존 항목과 함께 이어받는다`() {
        invite()
        val legacy = accountService.create("legacy@example.com", null, admin = false)
        items.save(WatchItem(requireNotNull(legacy.id), WatchItemType.KEYWORD, "타당성", "0 */5 * * * *", now))
        signup("ABCD-2345", "legacy@example.com").andExpect { status { isAccepted() } }

        verify(lastTokenFor("legacy@example.com")).andExpect { status { isNoContent() } }

        val claimed = requireNotNull(users.findByEmail("legacy@example.com"))
        assertThat(claimed.id).isEqualTo(legacy.id)
        assertThat(claimed.isClaimed).isTrue()
        assertThat(items.findAllByUserIdOrderByIdAsc(requireNotNull(claimed.id)).map { it.value }).containsExactly("타당성")
    }

    @Test
    fun `이미 가입된 이메일은 가입 요청과 확인에서 모두 409`() {
        invite()
        accountService.create("member@example.com", "hash", admin = false)
        signup("ABCD-2345", "member@example.com").andExpect { status { isConflict() } }

        signup("ABCD-2345", "twice@example.com")
        val first = lastTokenFor("twice@example.com")
        signup("ABCD-2345", "twice@example.com")
        val second = lastTokenFor("twice@example.com")
        verify(first).andExpect { status { isNoContent() } }
        verify(second).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ALREADY_REGISTERED") }
        }
    }

    @Test
    fun `확인 링크는 한 번만 쓸 수 있고 24시간이 지나면 400`() {
        invite()
        signup("ABCD-2345", "new@example.com")
        val token = lastTokenFor("new@example.com")
        verify(token).andExpect { status { isNoContent() } }
        verify(token).andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }

        signup("ABCD-2345", "late@example.com")
        val late = lastTokenFor("late@example.com")
        em.flush()
        jdbc.update("UPDATE email_token SET expires_at = now() - interval '1 minute' WHERE email = 'late@example.com'")
        em.clear()
        verify(late).andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }
    }
}
