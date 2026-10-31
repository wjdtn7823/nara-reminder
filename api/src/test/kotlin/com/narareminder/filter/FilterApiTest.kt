package com.narareminder.filter

import com.narareminder.PostgresTestConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class FilterApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val termRepository: FilterTermRepository,
) {
    private fun postTerm(type: String, value: String) = mockMvc.post("/api/filters/terms") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"type":"$type","value":"$value"}"""
    }

    private fun idOf(type: FilterTermType, value: String): Long =
        termRepository.findAll().first { it.type == type && it.value == value }.id ?: error("id 없음")

    @Test
    fun `필터 전체를 조회한다`() {
        mockMvc.get("/api/filters").andExpect {
            status { isOk() }
            jsonPath("$.keywords.length()") { value(62) }
            jsonPath("$.keywords[0].value") { value("타당성") }
            jsonPath("$.agencies.length()") { value(10) }
            jsonPath("$.excludeKeywords.length()") { value(0) }
            jsonPath("$.categories.length()") { value(4) }
            jsonPath("$.priceMin") { value(null) }
        }
    }

    @Test
    fun `항목을 추가하면 201과 함께 조회에 나온다`() {
        postTerm("EXCLUDE_KEYWORD", "  교육  ").andExpect {
            status { isCreated() }
            jsonPath("$.value") { value("교육") }
            jsonPath("$.type") { value("EXCLUDE_KEYWORD") }
        }
        mockMvc.get("/api/filters").andExpect { jsonPath("$.excludeKeywords[0].value") { value("교육") } }
    }

    @Test
    fun `공백만 있거나 100자를 넘으면 400`() {
        postTerm("KEYWORD", "   ").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_FILTER") }
        }
        postTerm("KEYWORD", "가".repeat(101)).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `대소문자만 다른 값은 중복으로 거부한다`() {
        postTerm("KEYWORD", "oda").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE") }
            jsonPath("$.message") { value("이미 등록된 키워드입니다: oda") }
        }
    }

    @Test
    fun `같은 값이라도 종류가 다르면 추가된다`() {
        postTerm("EXCLUDE_KEYWORD", "ODA").andExpect { status { isCreated() } }
    }

    @Test
    fun `알 수 없는 type 은 400`() {
        postTerm("FOO", "x").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("BAD_REQUEST") }
        }
    }

    @Test
    fun `항목 값을 수정한다`() {
        val id = idOf(FilterTermType.KEYWORD, "회계")
        mockMvc.put("/api/filters/terms/$id") {
            contentType = MediaType.APPLICATION_JSON; content = """{"value":"회계감사"}"""
        }.andExpect { status { isOk() }; jsonPath("$.value") { value("회계감사") } }
    }

    @Test
    fun `다른 항목과 같은 값으로 수정하면 409`() {
        val id = idOf(FilterTermType.KEYWORD, "회계")
        mockMvc.put("/api/filters/terms/$id") {
            contentType = MediaType.APPLICATION_JSON; content = """{"value":"원가"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `자기 자신의 대소문자만 바꾸는 수정은 허용한다`() {
        val id = idOf(FilterTermType.KEYWORD, "ODA")
        mockMvc.put("/api/filters/terms/$id") {
            contentType = MediaType.APPLICATION_JSON; content = """{"value":"oda"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `없는 항목 수정과 삭제는 404`() {
        mockMvc.put("/api/filters/terms/999999") {
            contentType = MediaType.APPLICATION_JSON; content = """{"value":"x"}"""
        }.andExpect { status { isNotFound() }; jsonPath("$.code") { value("NOT_FOUND") } }
        mockMvc.delete("/api/filters/terms/999999").andExpect { status { isNotFound() } }
    }

    @Test
    fun `항목을 삭제한다`() {
        val id = idOf(FilterTermType.AGENCY, "해외건설협회")
        mockMvc.delete("/api/filters/terms/$id").andExpect { status { isNoContent() } }
        mockMvc.get("/api/filters").andExpect { jsonPath("$.agencies.length()") { value(9) } }
    }

    @Test
    fun `업무구분과 가격 범위를 저장한다`() {
        mockMvc.put("/api/filters/settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"categories":["SERVICE"],"priceMin":10000000,"priceMax":null}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.categories.length()") { value(1) }
            jsonPath("$.priceMin") { value(10_000_000) }
        }
    }

    @Test
    fun `업무구분이 없거나 가격 범위가 잘못되면 400`() {
        listOf(
            """{"categories":[],"priceMin":null,"priceMax":null}""",
            """{"categories":["SERVICE"],"priceMin":200,"priceMax":100}""",
            """{"categories":["SERVICE"],"priceMin":-1,"priceMax":null}""",
            """{"categories":["SERVICE"],"priceMin":"abc","priceMax":null}""",
        ).forEach { body ->
            mockMvc.put("/api/filters/settings") {
                contentType = MediaType.APPLICATION_JSON; content = body
            }.andExpect { status { isBadRequest() } }
        }
    }
}
