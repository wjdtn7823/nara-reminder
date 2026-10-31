package com.narareminder.notice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoticeFilterTest {

    @Test
    fun `빈 조건은 모든 공고를 통과시킨다`() {
        assertThat(FilterCriteria().matches(aNotice(price = null, agency = null))).isTrue()
    }

    @Test
    fun `키워드 중 하나라도 공고명에 포함되면 통과한다 - 대소문자 무시`() {
        val filter = FilterCriteria(keywords = listOf("ai", "유지보수"))

        assertThat(filter.matches(aNotice(title = "차세대 AI 플랫폼"))).isTrue()
        assertThat(filter.matches(aNotice(title = "홈페이지 유지보수"))).isTrue()
        assertThat(filter.matches(aNotice(title = "청사 청소 용역"))).isFalse()
    }

    @Test
    fun `제외 키워드가 포함되면 탈락한다`() {
        val filter = FilterCriteria(keywords = listOf("AI"), excludeKeywords = listOf("교육"))

        assertThat(filter.matches(aNotice(title = "AI 교육 위탁"))).isFalse()
        assertThat(filter.matches(aNotice(title = "AI 플랫폼"))).isTrue()
    }

    @Test
    fun `가격 범위는 경계값을 포함한다`() {
        val filter = FilterCriteria(priceMin = 50_000_000, priceMax = 100_000_000)

        assertThat(filter.matches(aNotice(price = 50_000_000))).isTrue()
        assertThat(filter.matches(aNotice(price = 100_000_000))).isTrue()
        assertThat(filter.matches(aNotice(price = 49_999_999))).isFalse()
        assertThat(filter.matches(aNotice(price = 100_000_001))).isFalse()
    }

    @Test
    fun `가격 조건이 있어도 가격이 없는 공고는 통과한다`() {
        assertThat(FilterCriteria(priceMin = 50_000_000).matches(aNotice(price = null))).isTrue()
    }

    @Test
    fun `수요기관만 설정하면 부분일치로 매칭하고 기관명이 없으면 탈락한다`() {
        val filter = FilterCriteria(agencies = listOf("한국국제협력단"))

        assertThat(filter.matches(aNotice(agency = "한국국제협력단 사업운영실"))).isTrue()
        assertThat(filter.matches(aNotice(agency = "부산광역시"))).isFalse()
        assertThat(filter.matches(aNotice(agency = null))).isFalse()
    }

    @Test
    fun `키워드와 수요기관은 OR로 결합하고 제외 키워드는 양쪽에 적용한다`() {
        val filter = FilterCriteria(
            keywords = listOf("타당성"),
            agencies = listOf("한국국제협력단"),
            excludeKeywords = listOf("물품"),
        )

        assertThat(filter.matches(aNotice(title = "도로 타당성조사 용역", agency = "강원도"))).isTrue()
        assertThat(filter.matches(aNotice(title = "사무실 이전 용역", agency = "한국국제협력단"))).isTrue()
        assertThat(filter.matches(aNotice(title = "사무실 이전 용역", agency = "강원도"))).isFalse()
        assertThat(filter.matches(aNotice(title = "사무용 물품 구매", agency = "한국국제협력단"))).isFalse()
    }
}
