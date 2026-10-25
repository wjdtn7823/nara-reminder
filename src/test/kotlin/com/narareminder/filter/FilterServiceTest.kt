package com.narareminder.filter

import com.narareminder.PostgresTestConfig
import com.narareminder.notice.NoticeCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class FilterServiceTest @Autowired constructor(
    private val termRepository: FilterTermRepository,
    private val settingRepository: FilterSettingRepository,
) {
    private val service = FilterService(termRepository, settingRepository, Clock.systemUTC())

    @Test
    fun `yml 필터가 이관되어 있다`() {
        val criteria = service.current()

        assertThat(criteria.keywords).hasSize(62).startsWith("타당성", "타당성조사").endsWith("지역개발")
        assertThat(criteria.agencies).hasSize(10).contains("한국국제협력단", "한국환경산업기술원")
        assertThat(criteria.excludeKeywords).isEmpty()
        assertThat(criteria.categories).containsExactly(
            NoticeCategory.GOODS, NoticeCategory.SERVICE, NoticeCategory.CONSTRUCTION, NoticeCategory.FOREIGN,
        )
        assertThat(criteria.priceMin).isNull()
        assertThat(criteria.priceMax).isNull()
    }

    @Test
    fun `DB 변경이 다음 조회에 바로 반영된다`() {
        termRepository.save(FilterTerm(FilterTermType.EXCLUDE_KEYWORD, "교육", java.time.Instant.now()))
        settingRepository.findById(1).ifPresent { it.priceMin = 1_000; it.categories = "SERVICE" }
        termRepository.flush(); settingRepository.flush()

        val criteria = service.current()

        assertThat(criteria.excludeKeywords).containsExactly("교육")
        assertThat(criteria.categories).containsExactly(NoticeCategory.SERVICE)
        assertThat(criteria.priceMin).isEqualTo(1_000L)
    }
}
