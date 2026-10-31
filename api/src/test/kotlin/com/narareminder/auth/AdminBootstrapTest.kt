package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.config.AppProperties
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilterRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class AdminBootstrapTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), KST)

    private fun bootstrap(email: String) =
        AdminBootstrap(AppProperties(adminEmail = email), users, AccountService(users, filters, clock))
            .run(DefaultApplicationArguments())

    @Test
    fun `관리자 계정이 없으면 비밀번호 없이 만든다`() {
        bootstrap(" Admin@Example.com ")

        val admin = users.findByEmail("admin@example.com")
        assertThat(admin?.isAdmin).isTrue()
        assertThat(admin?.passwordHash).isNull()
        assertThat(filters.findById(requireNotNull(admin?.id))).isPresent()
    }

    @Test
    fun `이미 있는 계정이면 관리자 권한만 준다`() {
        users.save(AppUser("member@example.com", "hash", false, clock.instant()))

        bootstrap("member@example.com")

        assertThat(users.findByEmail("member@example.com")?.isAdmin).isTrue()
        assertThat(users.findByEmail("member@example.com")?.passwordHash).isEqualTo("hash")
    }

    @Test
    fun `설정이 비어 있으면 아무것도 하지 않는다`() {
        val before = users.count()

        bootstrap("")

        assertThat(users.count()).isEqualTo(before)
    }
}
