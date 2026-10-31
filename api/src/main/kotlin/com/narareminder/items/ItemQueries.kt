package com.narareminder.items

import com.narareminder.cadence.Cadence
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.notice.NoticeSource
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

// 상세 화면용 조회. 여러 테이블을 묶어 읽기만 하므로 JPA 대신 SQL 로 쓴다
@Component
class ItemQueries(private val jdbc: NamedParameterJdbcTemplate) {

    fun stats(itemId: Long, since: Instant): ItemStats = requireNotNull(
        jdbc.queryForObject(
            """
            SELECT count(*) AS matched,
                   count(*) FILTER (WHERE d.status = 'SENT') AS sent,
                   count(*) FILTER (WHERE d.status = 'PENDING') AS pending
            FROM delivery_item di JOIN delivery d ON d.id = di.delivery_id
            WHERE di.watch_item_id = :itemId AND d.matched_at >= :since
            """,
            mapOf("itemId" to itemId, "since" to Timestamp.from(since)),
            RowMapper { rs, _ -> ItemStats(rs.getInt("matched"), rs.getInt("sent"), rs.getInt("pending")) },
        ),
    )

    fun recent(itemId: Long): List<RecentNotice> {
        val rows = jdbc.query(
            """
            SELECT d.id, d.status, d.matched_at, d.sent_at, n.source, n.notice_no, n.notice_ord, n.title,
                   n.agency, n.price, n.deadline_at, n.detail_url
            FROM delivery_item di
            JOIN delivery d ON d.id = di.delivery_id
            JOIN notice n ON n.source = d.source AND n.notice_no = d.notice_no AND n.notice_ord = d.notice_ord
            WHERE di.watch_item_id = :itemId
            ORDER BY d.matched_at DESC, d.id DESC
            LIMIT $RECENT_LIMIT
            """,
            mapOf("itemId" to itemId),
            RowMapper { rs, _ ->
                val source = NoticeSource.valueOf(rs.getString("source"))
                val ord = rs.getString("notice_ord")
                RecentNotice(
                    id = rs.getLong("id"),
                    status = DeliveryStatus.valueOf(rs.getString("status")),
                    matchedAt = rs.getTimestamp("matched_at").toInstant(),
                    sentAt = rs.getTimestamp("sent_at")?.toInstant(),
                    scheduledAt = null,
                    source = source,
                    noticeNo = rs.getString("notice_no"),
                    title = rs.getString("title"),
                    agency = rs.getString("agency"),
                    price = rs.getObject("price") as Long?,
                    deadlineAt = rs.getTimestamp("deadline_at")?.toLocalDateTime(),
                    detailUrl = rs.getString("detail_url"),
                    revision = source == NoticeSource.BID && (ord.toIntOrNull() ?: 0) > 0,
                )
            },
        )
        val pendingIds = rows.filter { it.status == DeliveryStatus.PENDING }.map { it.id }
        if (pendingIds.isEmpty()) return rows
        // 대기 공고는 걸린 모든 항목의 주기 중 가장 빠른 시각에 나간다
        val cronsByDelivery = jdbc.query(
            "SELECT di.delivery_id, w.cron FROM delivery_item di JOIN watch_item w ON w.id = di.watch_item_id WHERE di.delivery_id IN (:ids)",
            mapOf("ids" to pendingIds),
            RowMapper { rs, _ -> rs.getLong("delivery_id") to rs.getString("cron") },
        ).groupBy({ it.first }, { it.second })
        return rows.map { row ->
            if (row.status != DeliveryStatus.PENDING) row
            else row.copy(scheduledAt = Cadence.earliestFire(cronsByDelivery[row.id].orEmpty(), row.matchedAt))
        }
    }

    companion object {
        private const val RECENT_LIMIT = 20
    }
}
