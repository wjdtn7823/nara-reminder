package com.narareminder.matching

import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.aNotice
import com.narareminder.watch.UserFilter
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class UserProfileTest {

    private val now = Instant.parse("2026-10-31T05:00:00Z")
    private val keyword = WatchItem(1, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now, id = 10)
    private val agency = WatchItem(1, WatchItemType.AGENCY, "조달청", "0 0 9 * * *", now, id = 11)

    private fun profile(filter: UserFilter = UserFilter(1)) = UserProfile(1, filter, listOf(keyword, agency))

    @Test
    fun `키워드는 공고명, 수요기관은 수요기관명에 포함되면 걸린다 (대소문자 무시)`() {
        assertThat(profile().match(aNotice(title = "AI 플랫폼 구축", agency = "조달청"))).containsExactly(keyword, agency)
        assertThat(profile().match(aNotice(title = "청사 청소", agency = "서울특별시 조달청"))).containsExactly(agency)
        assertThat(profile().match(aNotice(title = "청사 청소", agency = null))).isEmpty()
    }

    @Test
    fun `제외 키워드가 공고명에 있으면 걸리지 않는다`() {
        val filter = UserFilter(1).apply { update(listOf("구축"), NoticeCategory.entries, null, null) }

        assertThat(profile(filter).match(aNotice(title = "AI 플랫폼 구축"))).isEmpty()
    }

    @Test
    fun `업무구분과 가격 범위를 벗어나면 걸리지 않고 가격이 없으면 통과한다`() {
        val goodsOnly = UserFilter(1).apply { update(emptyList(), listOf(NoticeCategory.GOODS), null, null) }
        val cheap = UserFilter(1).apply { update(emptyList(), NoticeCategory.entries, null, 1_000) }

        assertThat(profile(goodsOnly).match(aNotice(title = "플랫폼", category = NoticeCategory.SERVICE))).isEmpty()
        assertThat(profile(cheap).match(aNotice(title = "플랫폼", price = 5_000))).isEmpty()
        assertThat(profile(cheap).match(aNotice(title = "플랫폼", price = null, agency = null))).containsExactly(keyword)
    }
}
