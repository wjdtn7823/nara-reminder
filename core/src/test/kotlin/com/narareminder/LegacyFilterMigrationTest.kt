package com.narareminder

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
// Flyway 가 별도 커넥션으로 읽으므로 테스트 데이터는 트랜잭션 밖에서 커밋돼야 한다
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyFilterMigrationTest @Autowired constructor(private val dataSource: DataSource) {

    private val jdbc = JdbcTemplate(dataSource)

    // 테스트마다 별도 스키마에 V1~V4 까지 올린 뒤 기존 데이터를 넣고 V5 를 실행한다
    private fun flyway(schema: String, owner: String, target: String? = null): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .placeholders(mapOf("legacyowner" to owner))
            .apply { if (target != null) target(target) }
            .load()

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java) ?: 0

    @Test
    fun `키워드·수요기관·공통 조건·발송 이력을 이관 계정으로 옮긴다`() {
        flyway("legacy_ok", "x", target = "4").migrate()
        jdbc.update("INSERT INTO legacy_ok.filter_term (type, value, created_at) VALUES ('EXCLUDE_KEYWORD', '교육', now()), ('EXCLUDE_KEYWORD', '청소', now())")
        jdbc.update("UPDATE legacy_ok.filter_setting SET categories = 'SERVICE', price_min = 1000")
        jdbc.update(
            "INSERT INTO legacy_ok.notified_notice (source, notice_no, notice_ord, title, notified_at) " +
                "VALUES ('BID', 'N1', '000', '이미 보낸 공고', now()), ('PRE_SPEC', 'P1', '0', '이미 보낸 사전규격', now())",
        )

        flyway("legacy_ok", " Owner@Example.com ").migrate()

        val user = jdbc.queryForMap("SELECT id, email, password_hash, is_admin, status FROM legacy_ok.app_user")
        assertThat(user["email"]).isEqualTo("owner@example.com")
        assertThat(user["password_hash"]).isNull()
        assertThat(user["is_admin"]).isEqualTo(false)
        assertThat(user["status"]).isEqualTo("ACTIVE")
        assertThat(count("SELECT count(*) FROM legacy_ok.watch_item WHERE type = 'KEYWORD' AND cron = '0 */5 * * * *'")).isEqualTo(62)
        assertThat(count("SELECT count(*) FROM legacy_ok.watch_item WHERE type = 'AGENCY' AND cron = '0 */5 * * * *'")).isEqualTo(10)
        val filter = jdbc.queryForMap("SELECT exclude_keywords, categories, price_min, price_max FROM legacy_ok.user_filter")
        assertThat(filter["exclude_keywords"]).isEqualTo("교육\n청소")
        assertThat(filter["categories"]).isEqualTo("SERVICE")
        assertThat(filter["price_min"]).isEqualTo(1000L)
        assertThat(filter["price_max"]).isNull()
        assertThat(count("SELECT count(*) FROM legacy_ok.notice")).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM legacy_ok.delivery")).isZero()
    }

    @Test
    fun `기존 필터가 있는데 이관 계정이 비어 있으면 마이그레이션이 실패한다`() {
        assertThatThrownBy { flyway("legacy_missing", "").migrate() }
            .hasStackTraceContaining("LEGACY_OWNER_EMAIL")
    }

    @Test
    fun `기존 필터가 없으면 아무것도 만들지 않는다`() {
        flyway("legacy_empty", "", target = "4").migrate()
        jdbc.update("DELETE FROM legacy_empty.filter_term")

        flyway("legacy_empty", "").migrate()

        assertThat(count("SELECT count(*) FROM legacy_empty.app_user")).isZero()
    }
}
