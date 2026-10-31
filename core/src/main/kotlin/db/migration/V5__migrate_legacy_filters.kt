package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

// 단일 사용자 시절 필터를 LEGACY_OWNER_EMAIL 계정으로 옮긴다.
// 공개 저장소라 메일 주소를 SQL 에 쓰지 않고 Flyway placeholder(legacyowner)로 받는다
class V5__migrate_legacy_filters : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val connection = context.connection
        if (connection.count("SELECT count(*) FROM filter_term") == 0) return

        val email = context.configuration.placeholders["legacyowner"].orEmpty().trim().lowercase()
        check(email.isNotEmpty()) { "기존 필터를 옮길 계정이 없습니다. LEGACY_OWNER_EMAIL 을 설정하세요" }

        val userId = connection.prepareStatement(
            "INSERT INTO app_user (email, is_admin, status, created_at) VALUES (?, FALSE, 'ACTIVE', now()) RETURNING id",
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

        connection.update(
            """
            INSERT INTO watch_item (user_id, type, value, cron, created_at)
            SELECT ?, type, value, '0 */5 * * * *', created_at FROM filter_term
            WHERE type IN ('KEYWORD', 'AGENCY') ORDER BY id
            ON CONFLICT DO NOTHING
            """,
            userId,
        )
        connection.update(
            """
            INSERT INTO user_filter (user_id, exclude_keywords, categories, price_min, price_max)
            SELECT ?,
                   coalesce((SELECT string_agg(value, E'\n' ORDER BY id) FROM filter_term WHERE type = 'EXCLUDE_KEYWORD'), ''),
                   s.categories, s.price_min, s.price_max
            FROM filter_setting s WHERE s.id = 1
            """,
            userId,
        )
        // 이미 보낸 공고 키를 넣어 두면 전환 직후 조회 구간이 겹쳐도 새 공고로 매칭되지 않는다
        connection.createStatement().use {
            it.executeUpdate(
                "INSERT INTO notice (source, notice_no, notice_ord, title, collected_at) " +
                    "SELECT source, notice_no, notice_ord, title, notified_at FROM notified_notice",
            )
        }
    }

    private fun Connection.count(sql: String): Int =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun Connection.update(sql: String, userId: Long) {
        prepareStatement(sql.trimIndent()).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }
}
