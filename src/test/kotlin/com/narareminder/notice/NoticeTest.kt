package com.narareminder.notice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoticeTest {

    private fun notice(source: NoticeSource, ord: String) = Notice(
        source = source, noticeNo = "N1", noticeOrd = ord, title = "t", category = NoticeCategory.SERVICE,
        price = null, agency = null, postedAt = null, deadlineAt = null, detailUrl = "https://www.g2b.go.kr",
    )

    @Test
    fun `입찰공고는 차수가 0보다 크면 정정공고다`() {
        assertThat(notice(NoticeSource.BID, "000").isRevision).isFalse()
        assertThat(notice(NoticeSource.BID, "001").isRevision).isTrue()
    }

    @Test
    fun `사전규격은 정정공고가 아니다`() {
        assertThat(notice(NoticeSource.PRE_SPEC, "0").isRevision).isFalse()
    }
}
