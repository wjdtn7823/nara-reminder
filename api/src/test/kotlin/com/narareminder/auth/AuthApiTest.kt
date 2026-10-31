package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.support.authAs
import com.narareminder.user.AppUserRepository
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
// csrf() 후처리기는 공용 CsrfFilter 의 저장소를 갈아 끼워 쿠키 검증을 깨뜨리므로, 쿠키 테스트를 먼저 돌리고 컨텍스트를 버린다
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AuthApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    private fun member(email: String = "member@example.com", password: String? = "correct-horse") =
        accountService.create(email, password?.let { passwordEncoder.encode(it) }, admin = false)

    private fun login(email: String, password: String, session: MockHttpSession = MockHttpSession()): ResultActionsDsl =
        mockMvc.post("/api/auth/login") {
            this.session = session
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }

    @Test
    fun `로그인하면 세션에 인증이 저장되고 내 정보를 돌려준다`() {
        member()
        val session = MockHttpSession()

        login("member@example.com", "correct-horse", session).andExpect {
            status { isOk() }
            jsonPath("$.email") { value("member@example.com") }
            jsonPath("$.admin") { value(false) }
        }
        mockMvc.get("/api/me") { this.session = session }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("member@example.com") }
        }
    }

    @Test
    fun `이메일 대소문자와 앞뒤 공백은 무시한다`() {
        member()

        login("  Member@Example.COM ", "correct-horse").andExpect { status { isOk() } }
    }

    @Test
    fun `비밀번호가 틀리거나 없는 계정이면 401`() {
        member()
        member("unclaimed@example.com", password = null)

        login("member@example.com", "wrong-password").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
        login("unclaimed@example.com", "anything-here").andExpect { status { isUnauthorized() } }
        login("nobody@example.com", "anything-here").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `5번 연속 틀리면 15분 동안 잠기고 잠금이 지나면 다시 로그인된다`() {
        val user = member()
        repeat(5) { login("member@example.com", "wrong-password").andExpect { status { isUnauthorized() } } }

        login("member@example.com", "correct-horse").andExpect {
            status { isLocked() }
            jsonPath("$.code") { value("ACCOUNT_LOCKED") }
        }

        user.lockedUntil = Instant.now().minusSeconds(1)
        users.saveAndFlush(user)
        login("member@example.com", "correct-horse").andExpect { status { isOk() } }
    }

    @Test
    @Order(1)
    fun `로그인하지 않으면 401 이고 XSRF-TOKEN 쿠키를 내려준다`() {
        mockMvc.get("/api/me").andExpect {
            status { isUnauthorized() }
            cookie { exists("XSRF-TOKEN") }
        }
    }

    @Test
    fun `CSRF 토큰 없는 변경 요청은 403`() {
        member()

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"member@example.com","password":"correct-horse"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `일반 사용자가 관리자 API 를 부르면 403`() {
        val user = member()

        mockMvc.get("/api/admin/runs") { with(authAs(requireNotNull(user.id))) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `로그아웃하면 세션이 끝난다`() {
        member()
        val session = MockHttpSession()
        login("member@example.com", "correct-horse", session).andExpect { status { isOk() } }

        mockMvc.post("/api/auth/logout") {
            this.session = session
            with(csrf())
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/me") { this.session = session }.andExpect { status { isUnauthorized() } }
    }
}
