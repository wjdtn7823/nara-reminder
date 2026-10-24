# 나라장터 공고 알림 서버 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 나라장터 입찰공고(5분 주기)와 사전규격(매일 08:00)을 수집해 필터 조건에 맞는 새 공고를 이메일로 보내고, 반복 실패 시 에러 알림을 보내는 Spring Boot 서버를 Docker Compose로 실행 가능하게 만든다.

**Architecture:** 단일 Spring Boot(Kotlin) 애플리케이션. `@Scheduled` suspend 함수가 `NoticePipeline`을 호출하고, 파이프라인은 `NaraApiClient`(WebClient + 코루틴)로 조회 → 필터(순수 함수) → PostgreSQL 발송 이력으로 중복 제외 → `MailNotifier` 발송 → 이력/작업 상태 기록 순으로 동작한다. 실패 시 `job_state.last_success_at`을 갱신하지 않아 다음 배치가 같은 구간을 재조회한다.

**Tech Stack:** Kotlin 2.2.20, JDK 21 (Temurin), Spring Boot 3.5.6 (webflux, data-jpa, mail), kotlinx-coroutines-reactor, PostgreSQL 16, Flyway, JUnit5, MockK 1.14.5, MockWebServer 4.12.0, Testcontainers, Docker Compose

**Spec:** `docs/superpowers/specs/2026-10-24-nara-reminder-design.md`

## Global Constraints

- 언어: 주석·로그·메일 문구·커밋 본문은 한국어, 식별자는 영어
- Kotlin: `!!` 금지, data class / extension function / scope function 활용, 비동기는 코루틴
- 시간대: 모든 표시·API 파라미터는 `Asia/Seoul`(KST). DB에는 `Instant`(TIMESTAMPTZ) 저장
- 패키지 루트: `com.narareminder`
- 설정 키 (스펙 4절): `nara.service-key`, `nara.mail.{from,to}`, `nara.bid.{poll-interval,overlap,initial-lookback,max-lookback,alert-after-failures}`, `nara.pre-spec.{cron,overlap,initial-lookback,max-lookback,alert-after-failures}`, `nara.filter.{keywords,exclude-keywords,categories,price-min,price-max,agencies}`. 구현용 추가 키: `nara.api.{base-url,page-size,timeout}`, `nara.retry-delays`, `nara.scheduling.enabled`
- 주기: 입찰공고 `fixedDelay` 5분(`PT5M`), 사전규격 cron `0 0 8 * * *` (Asia/Seoul)
- 재시도: API·메일 모두 1s, 2s, 4s 백오프(최대 3회 재시도). HTTP 200 + 비정상 결과코드는 재시도하지 않음
- 에러 알림 임계치: 입찰공고 6회, 사전규격 1회 연속 실패. 장애 1건당 1회만 발송
- 결과코드 `00` 정상, `03` 데이터 없음(정상 0건), 그 외 실패
- 비밀값(`NARA_SERVICE_KEY`, `SMTP_*`, `DB_PASSWORD`)은 `.env`로 주입하고 커밋 금지
- 커밋: Conventional Commits `<type>(<scope>): <제목>`, 한국어 본문, 마지막 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 테스트 실행: `./gradlew test` (Testcontainers 테스트는 Docker 데몬 필요)

## Review Focus

1. **서비스키에 `+`, `/`, `=`가 포함된 경우** — 쿼리에 그대로 넣으면 서버가 `+`를 공백으로 해석해 인증 실패한다. 디코딩 키를 URI 변수로 넘겨 `%2B %2F %3D`로 엄격 인코딩돼야 한다. → Task 4 `서비스키는 엄격하게 인코딩된다`
2. **HTTP 200인데 본문이 XML 에러(`SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 등)인 경우** — JSON 파싱 예외로 뭉개지지 않고 원문 일부를 담은 비재시도 실패로 처리돼야 한다. → Task 4 `JSON이 아닌 응답은 재시도 없이 실패한다`
3. **공고명에 `&`, `<` 같은 HTML 특수문자가 있는 경우** — 메일 HTML이 깨지거나 태그가 주입되면 안 된다. → Task 6 `공고명은 HTML 이스케이프된다`
4. **한 항목의 가격/날짜가 빈 문자열·소수 표기·이상 형식인 경우** — 그 항목 하나 때문에 업무구분 전체가 매 배치 실패(영구 장애)하면 안 되고, 필드만 `null`로 처리돼야 한다. → Task 4 `빈 값과 소수 가격과 이상한 날짜를 관대하게 매핑한다`
5. **같은 실행에서 동일 공고가 두 번 조회되는 경우** (페이지 경계에서 신규 등록으로 항목이 밀림) — 메일에 한 번만 나와야 한다. → Task 7 `같은 실행에서 중복 조회된 공고는 한 번만 보낸다`

---

## File Structure

```
.tool-versions                         # asdf JDK 고정
.gitignore / .dockerignore
settings.gradle.kts / build.gradle.kts / gradlew / gradle/wrapper/*
Dockerfile / docker-compose.yml / .env.example / README.md
config/application.yml                 # 컨테이너에 마운트하는 필터 설정 (재빌드 없이 수정)
src/main/kotlin/com/narareminder/
  NaraReminderApplication.kt           # 진입점, Clock 빈
  config/NaraProperties.kt             # @ConfigurationProperties
  config/SchedulingConfig.kt           # @EnableScheduling (nara.scheduling.enabled)
  support/Time.kt                      # KST 상수
  support/Retry.kt                     # retrying() 백오프 재시도
  notice/Notice.kt                     # Notice, NoticeSource, NoticeCategory
  notice/NoticeFilter.kt               # Filter.matches() 확장 함수
  client/NaraApiClient.kt              # API 호출, 페이지 순회, 결과코드 검증
  client/NaraApiException.kt
  client/NoticeMapper.kt               # JSON item → Notice
  store/NotifiedNotice.kt              # 발송 이력 엔티티 + NotifiedNoticeId + Notice.notifiedId()
  store/NotifiedNoticeRepository.kt
  job/JobName.kt / job/JobState.kt / job/JobStateRepository.kt
  job/QueryWindow.kt                   # 조회 구간 계산 (순수 함수)
  job/NoticePipeline.kt                # 조회→필터→중복제외→발송→기록, 실패/알림 처리
  job/NoticeJobs.kt                    # @Scheduled 진입점
  mail/MailNotifier.kt                 # 공고 메일, 에러 알림 메일
src/main/resources/application.yml
src/main/resources/db/migration/V1__init.sql
src/test/kotlin/com/narareminder/...  # 각 Task 참조
src/test/resources/application-test.yml
src/test/resources/fixtures/*.json
```

---

### Task 1: 실제 API 응답 실측 (가정 확정)

코드 없이 실제 서비스키로 API를 호출해 스펙 10절의 가정 5개를 확정한다. **서비스키가 없으면 이 Task에서 멈추고 사용자에게 발급(공공데이터포털에서 `입찰공고정보서비스`, `사전규격정보서비스` 두 건 활용신청)을 요청한다.** 응답에는 담당자 이름·전화번호가 들어 있으므로 원본 응답을 저장소에 커밋하지 않는다.

**Files:**
- Modify: `docs/superpowers/specs/2026-10-24-nara-reminder-design.md` (10절 "실측으로 확정할 항목")

**Interfaces:**
- Produces: 확정값 5개 — Task 4의 `INQUIRY_BY_REGISTERED_AT`, `QUERY_DATE_TIME`, `RESPONSE_DATE_TIME`, `items` 파싱 분기, Task 2의 `Notice.isRevision` 기준, `nara.api.page-size`

- [ ] **Step 1: 키 준비 (디코딩 키를 URL 인코딩)**

```bash
export NARA_SERVICE_KEY='<공공데이터포털 마이페이지의 Decoding 키>'
KEY=$(python3 -c 'import os,urllib.parse;print(urllib.parse.quote(os.environ["NARA_SERVICE_KEY"],safe=""))')
TODAY=$(date +%Y%m%d)
BASE=https://apis.data.go.kr/1230000
```

- [ ] **Step 2: 입찰공고(용역) 정상 조회**

```bash
curl -s "$BASE/ad/BidPublicInfoService/getBidPblancListInfoServc?serviceKey=$KEY&pageNo=1&numOfRows=3&type=json&inqryDiv=1&inqryBgnDt=${TODAY}0000&inqryEndDt=${TODAY}2359" | python3 -m json.tool | head -120
```

확인: `response.header.resultCode`가 `00`인지 / `body.items`가 배열인지 `{"item":[...]}`인지 / `rgstDt`·`bidClseDt` 형식(`yyyy-MM-dd HH:mm:ss` 가정) / 최초 공고의 `bidNtceOrd` 표기(`000` 가정) / `presmptPrce` 표기 / `totalCount` 타입.

- [ ] **Step 3: 사전규격(용역) 정상 조회**

```bash
curl -s "$BASE/ao/HrcspSsstndrdInfoService/getPublicPrcureThngInfoServc?serviceKey=$KEY&pageNo=1&numOfRows=3&type=json&inqryDiv=1&inqryBgnDt=${TODAY}0000&inqryEndDt=${TODAY}2359" | python3 -m json.tool | head -80
```

확인: `inqryDiv=1`이 등록일시 기준으로 동작하는지(0건이거나 오류면 `2`, `3`을 시도해 등록일시 기준 코드를 찾는다) / `asignBdgtAmt`, `opninRgstClseDt`, `rgstDt` 형식.

- [ ] **Step 4: 데이터 없음 / 페이지 크기 한도 확인**

```bash
# 미래 1분 구간 → 데이터 없음 응답 형태
curl -s "$BASE/ad/BidPublicInfoService/getBidPblancListInfoServc?serviceKey=$KEY&pageNo=1&numOfRows=3&type=json&inqryDiv=1&inqryBgnDt=209901010000&inqryEndDt=209901010001"
# 페이지 크기 한도 (07 이면 초과)
curl -s "$BASE/ad/BidPublicInfoService/getBidPblancListInfoServc?serviceKey=$KEY&pageNo=1&numOfRows=1000&type=json&inqryDiv=1&inqryBgnDt=${TODAY}0000&inqryEndDt=${TODAY}2359" | head -c 300
```

- [ ] **Step 5: 스펙 10절에 결과 기록 후 커밋**

"실측으로 확정할 항목" 목록의 각 줄 끝에 `→ 확인: <실측값>`을 적는다. 가정과 다른 항목이 있으면 그 값을 Task 2·4 구현 시 그대로 반영해야 하므로 해당 Task의 상수/fixture도 함께 바꾼다고 메모한다.

```bash
git add docs/superpowers/specs/2026-10-24-nara-reminder-design.md
git commit -m "docs(spec): 나라장터 API 실측 결과 기록

조회구분 코드, 날짜 형식, 차수 표기, 빈 결과 형태, 페이지 한도를 실제 호출로 확정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 프로젝트 골격, 설정, 공고 모델

**Files:**
- Create: `.tool-versions`, `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, gradle wrapper
- Create: `src/main/kotlin/com/narareminder/NaraReminderApplication.kt`
- Create: `src/main/kotlin/com/narareminder/support/Time.kt`
- Create: `src/main/kotlin/com/narareminder/config/NaraProperties.kt`
- Create: `src/main/kotlin/com/narareminder/notice/Notice.kt`
- Create: `src/main/resources/application.yml`
- Test: `src/test/kotlin/com/narareminder/config/NaraPropertiesTest.kt`, `src/test/kotlin/com/narareminder/notice/NoticeTest.kt`

**Interfaces:**
- Produces:
  - `val KST: ZoneId`
  - `NaraProperties(serviceKey: String, mail: Mail, bid: Job, preSpec: Job, api: Api = Api(), filter: Filter = Filter(), retryDelays: List<Duration> = [1s,2s,4s])`
  - `NaraProperties.Api(baseUrl: String, pageSize: Int, timeout: Duration)`, `Mail(from: String, to: List<String>)`, `Job(overlap, initialLookback, maxLookback: Duration, alertAfterFailures: Int)`, `Filter(keywords, excludeKeywords: List<String>, categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?, agencies: List<String>)`
  - `enum NoticeSource(label, servicePath, operationPrefix) { BID, PRE_SPEC }`
  - `enum NoticeCategory(label, operationSuffix) { GOODS, SERVICE, CONSTRUCTION, FOREIGN }`
  - `data class Notice(source, noticeNo, noticeOrd, title, category, price: Long?, agency: String?, postedAt: LocalDateTime?, deadlineAt: LocalDateTime?, detailUrl: String)` + `val isRevision: Boolean`

- [ ] **Step 1: 빌드 파일 작성**

`.tool-versions`:
```
java temurin-21.0.8+9.0.LTS
```

`.gitignore`:
```
.gradle/
build/
.kotlin/
.idea/
*.iml
.env
```

`settings.gradle.kts`:
```kotlin
rootProject.name = "nara-reminder"
```

`build.gradle.kts`:
```kotlin
plugins {
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    kotlin("plugin.jpa") version "2.2.20"
}

group = "com.narareminder"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core")
    }
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Gradle wrapper 생성**

Run: `gradle wrapper --gradle-version 8.14.3`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` 생성

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/config/NaraPropertiesTest.kt`:
```kotlin
package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class NaraPropertiesTest {

    private val required = mapOf(
        "nara.service-key" to "key",
        "nara.mail.from" to "bot@example.com",
        "nara.mail.to" to "a@example.com,b@example.com",
        "nara.bid.overlap" to "30m",
        "nara.bid.initial-lookback" to "1h",
        "nara.bid.max-lookback" to "7d",
        "nara.bid.alert-after-failures" to "6",
        "nara.pre-spec.overlap" to "1h",
        "nara.pre-spec.initial-lookback" to "24h",
        "nara.pre-spec.max-lookback" to "7d",
        "nara.pre-spec.alert-after-failures" to "1",
    )

    private fun bind(vararg extra: Pair<String, String>): NaraProperties =
        Binder(MapConfigurationPropertySource(required + extra))
            .bind("nara", NaraProperties::class.java)
            .get()

    @Test
    fun `필수 설정과 기간 표기를 바인딩한다`() {
        val props = bind()

        assertThat(props.mail.to).containsExactly("a@example.com", "b@example.com")
        assertThat(props.bid.overlap).isEqualTo(Duration.ofMinutes(30))
        assertThat(props.bid.maxLookback).isEqualTo(Duration.ofDays(7))
        assertThat(props.preSpec.alertAfterFailures).isEqualTo(1)
        assertThat(props.api.pageSize).isEqualTo(100)
        assertThat(props.retryDelays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
    }

    @Test
    fun `필터 기본값은 전체 업무구분과 빈 조건이다`() {
        val filter = bind().filter

        assertThat(filter.categories).containsExactlyElementsOf(NoticeCategory.entries)
        assertThat(filter.keywords).isEmpty()
        assertThat(filter.priceMin).isNull()
    }

    @Test
    fun `필터 조건을 바인딩한다`() {
        val filter = bind(
            "nara.filter.keywords" to "AI,유지보수",
            "nara.filter.categories" to "SERVICE,GOODS",
            "nara.filter.price-min" to "50000000",
        ).filter

        assertThat(filter.keywords).containsExactly("AI", "유지보수")
        assertThat(filter.categories).containsExactly(NoticeCategory.SERVICE, NoticeCategory.GOODS)
        assertThat(filter.priceMin).isEqualTo(50_000_000L)
    }
}
```

`src/test/kotlin/com/narareminder/notice/NoticeTest.kt`:
```kotlin
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
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test`
Expected: FAIL — `Unresolved reference: NaraProperties`, `Notice` 등 컴파일 에러

- [ ] **Step 5: 구현**

`src/main/kotlin/com/narareminder/support/Time.kt`:
```kotlin
package com.narareminder.support

import java.time.ZoneId

val KST: ZoneId = ZoneId.of("Asia/Seoul")
```

`src/main/kotlin/com/narareminder/notice/Notice.kt`:
```kotlin
package com.narareminder.notice

import java.time.LocalDateTime

enum class NoticeSource(val label: String, val servicePath: String, val operationPrefix: String) {
    BID("입찰공고", "ad/BidPublicInfoService", "getBidPblancListInfo"),
    PRE_SPEC("사전규격", "ao/HrcspSsstndrdInfoService", "getPublicPrcureThngInfo"),
}

enum class NoticeCategory(val label: String, val operationSuffix: String) {
    GOODS("물품", "Thng"),
    SERVICE("용역", "Servc"),
    CONSTRUCTION("공사", "Cnstwk"),
    FOREIGN("외자", "Frgcpt"),
}

data class Notice(
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
    val title: String,
    val category: NoticeCategory,
    // BID: 추정가격 / PRE_SPEC: 배정예산
    val price: Long?,
    // BID: 수요기관 / PRE_SPEC: 실수요기관
    val agency: String?,
    val postedAt: LocalDateTime?,
    // BID: 입찰마감 / PRE_SPEC: 의견등록마감
    val deadlineAt: LocalDateTime?,
    val detailUrl: String,
) {
    val isRevision: Boolean
        get() = source == NoticeSource.BID && (noticeOrd.toIntOrNull() ?: 0) > 0
}
```

`src/main/kotlin/com/narareminder/config/NaraProperties.kt`:
```kotlin
package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("nara")
data class NaraProperties(
    val serviceKey: String,
    val mail: Mail,
    val bid: Job,
    val preSpec: Job,
    val api: Api = Api(),
    val filter: Filter = Filter(),
    val retryDelays: List<Duration> = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
) {
    data class Api(
        val baseUrl: String = "https://apis.data.go.kr/1230000",
        val pageSize: Int = 100,
        val timeout: Duration = Duration.ofSeconds(10),
    )

    data class Mail(
        val from: String,
        val to: List<String>,
    )

    data class Job(
        val overlap: Duration,
        val initialLookback: Duration,
        val maxLookback: Duration,
        val alertAfterFailures: Int,
    )

    data class Filter(
        val keywords: List<String> = emptyList(),
        val excludeKeywords: List<String> = emptyList(),
        val categories: List<NoticeCategory> = NoticeCategory.entries,
        val priceMin: Long? = null,
        val priceMax: Long? = null,
        val agencies: List<String> = emptyList(),
    )
}
```

`src/main/kotlin/com/narareminder/NaraReminderApplication.kt`:
```kotlin
package com.narareminder

import com.narareminder.support.KST
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class NaraReminderApplication {

    @Bean
    fun clock(): Clock = Clock.system(KST)
}

fun main(args: Array<String>) {
    runApplication<NaraReminderApplication>(*args)
}
```

`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: nara-reminder
  main:
    web-application-type: none
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/nara}
    username: ${DB_USERNAME:nara}
    password: ${DB_PASSWORD:nara}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  mail:
    host: ${SMTP_HOST}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

nara:
  service-key: ${NARA_SERVICE_KEY}
  mail:
    from: ${MAIL_FROM}
    to: ${MAIL_TO}
  bid:
    poll-interval: PT5M
    overlap: 30m
    initial-lookback: 1h
    max-lookback: 7d
    alert-after-failures: 6
  pre-spec:
    cron: "0 0 8 * * *"
    overlap: 1h
    initial-lookback: 24h
    max-lookback: 7d
    alert-after-failures: 1
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS (5 tests)

- [ ] **Step 7: 커밋**

```bash
git add .tool-versions .gitignore settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src
git commit -m "feat(core): 프로젝트 골격과 설정, 공고 모델 추가

Spring Boot/Kotlin 빌드 구성과 nara.* 설정 바인딩, 입찰공고/사전규격 공통 모델을 정의

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 공고 필터

**Files:**
- Create: `src/main/kotlin/com/narareminder/notice/NoticeFilter.kt`
- Create: `src/test/kotlin/com/narareminder/notice/NoticeFixtures.kt`
- Test: `src/test/kotlin/com/narareminder/notice/NoticeFilterTest.kt`

**Interfaces:**
- Consumes: `NaraProperties.Filter`, `Notice` (Task 2)
- Produces: `fun NaraProperties.Filter.matches(notice: Notice): Boolean`, 테스트 헬퍼 `aNotice(...)`

- [ ] **Step 1: 테스트 헬퍼와 실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/notice/NoticeFixtures.kt`:
```kotlin
package com.narareminder.notice

import java.time.LocalDateTime

fun aNotice(
    noticeNo: String = "R26BK00000001",
    noticeOrd: String = "000",
    title: String = "AI 플랫폼 구축",
    source: NoticeSource = NoticeSource.BID,
    category: NoticeCategory = NoticeCategory.SERVICE,
    price: Long? = 100_000_000,
    agency: String? = "조달청",
    postedAt: LocalDateTime? = LocalDateTime.of(2026, 10, 24, 13, 0),
    deadlineAt: LocalDateTime? = LocalDateTime.of(2026, 11, 3, 10, 0),
    detailUrl: String = "https://www.g2b.go.kr/notice/$noticeNo",
) = Notice(source, noticeNo, noticeOrd, title, category, price, agency, postedAt, deadlineAt, detailUrl)
```

`src/test/kotlin/com/narareminder/notice/NoticeFilterTest.kt`:
```kotlin
package com.narareminder.notice

import com.narareminder.config.NaraProperties.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoticeFilterTest {

    @Test
    fun `빈 조건은 모든 공고를 통과시킨다`() {
        assertThat(Filter().matches(aNotice(price = null, agency = null))).isTrue()
    }

    @Test
    fun `키워드 중 하나라도 공고명에 포함되면 통과한다 - 대소문자 무시`() {
        val filter = Filter(keywords = listOf("ai", "유지보수"))

        assertThat(filter.matches(aNotice(title = "차세대 AI 플랫폼"))).isTrue()
        assertThat(filter.matches(aNotice(title = "홈페이지 유지보수"))).isTrue()
        assertThat(filter.matches(aNotice(title = "청사 청소 용역"))).isFalse()
    }

    @Test
    fun `제외 키워드가 포함되면 탈락한다`() {
        val filter = Filter(keywords = listOf("AI"), excludeKeywords = listOf("교육"))

        assertThat(filter.matches(aNotice(title = "AI 교육 위탁"))).isFalse()
        assertThat(filter.matches(aNotice(title = "AI 플랫폼"))).isTrue()
    }

    @Test
    fun `가격 범위는 경계값을 포함한다`() {
        val filter = Filter(priceMin = 50_000_000, priceMax = 100_000_000)

        assertThat(filter.matches(aNotice(price = 50_000_000))).isTrue()
        assertThat(filter.matches(aNotice(price = 100_000_000))).isTrue()
        assertThat(filter.matches(aNotice(price = 49_999_999))).isFalse()
        assertThat(filter.matches(aNotice(price = 100_000_001))).isFalse()
    }

    @Test
    fun `가격 조건이 있어도 가격이 없는 공고는 통과한다`() {
        assertThat(Filter(priceMin = 50_000_000).matches(aNotice(price = null))).isTrue()
    }

    @Test
    fun `수요기관은 부분일치로 매칭하고 기관명이 없으면 탈락한다`() {
        val filter = Filter(agencies = listOf("서울"))

        assertThat(filter.matches(aNotice(agency = "서울특별시 강남구"))).isTrue()
        assertThat(filter.matches(aNotice(agency = "부산광역시"))).isFalse()
        assertThat(filter.matches(aNotice(agency = null))).isFalse()
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.notice.NoticeFilterTest'`
Expected: FAIL — `Unresolved reference: matches`

- [ ] **Step 3: 구현**

`src/main/kotlin/com/narareminder/notice/NoticeFilter.kt`:
```kotlin
package com.narareminder.notice

import com.narareminder.config.NaraProperties

fun NaraProperties.Filter.matches(notice: Notice): Boolean =
    matchesKeywords(notice.title) && matchesPrice(notice.price) && matchesAgency(notice.agency)

private fun NaraProperties.Filter.matchesKeywords(title: String): Boolean =
    (keywords.isEmpty() || keywords.any { title.contains(it, ignoreCase = true) }) &&
        excludeKeywords.none { title.contains(it, ignoreCase = true) }

// 가격이 없는 공고는 누락 방지를 위해 통과시킨다
private fun NaraProperties.Filter.matchesPrice(price: Long?): Boolean =
    price == null || ((priceMin == null || price >= priceMin) && (priceMax == null || price <= priceMax))

private fun NaraProperties.Filter.matchesAgency(agency: String?): Boolean =
    agencies.isEmpty() || (agency != null && agencies.any { agency.contains(it, ignoreCase = true) })
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.notice.NoticeFilterTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add src
git commit -m "feat(filter): 키워드/가격/수요기관 공고 필터 추가

설정된 조건을 AND, 목록 값은 OR로 매칭하고 가격 정보가 없는 공고는 누락 방지를 위해 통과

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 재시도 헬퍼와 나라장터 API 클라이언트

Task 1 실측 결과가 가정과 다르면 이 Task의 상수(`INQUIRY_BY_REGISTERED_AT`, `QUERY_DATE_TIME`, `RESPONSE_DATE_TIME`)와 fixture를 실측값에 맞춘다.

**Files:**
- Create: `src/main/kotlin/com/narareminder/support/Retry.kt`
- Create: `src/main/kotlin/com/narareminder/client/NaraApiException.kt`
- Create: `src/main/kotlin/com/narareminder/client/NoticeMapper.kt`
- Create: `src/main/kotlin/com/narareminder/client/NaraApiClient.kt`
- Create: `src/test/kotlin/com/narareminder/config/TestProperties.kt`
- Create: `src/test/resources/fixtures/bid-servc.json`, `src/test/resources/fixtures/pre-spec-servc.json`
- Test: `src/test/kotlin/com/narareminder/support/RetryTest.kt`, `src/test/kotlin/com/narareminder/client/NaraApiClientTest.kt`

**Interfaces:**
- Consumes: `NaraProperties`, `Notice`, `NoticeSource`, `NoticeCategory`, `KST` (Task 2)
- Produces:
  - `suspend fun <T> retrying(delays: List<Duration>, retryOn: (Throwable) -> Boolean = { true }, block: suspend () -> T): T`
  - `class NaraApiException(message: String, val retryable: Boolean, cause: Throwable? = null) : RuntimeException`
  - `@Component class NaraApiClient(webClientBuilder: WebClient.Builder, props: NaraProperties, objectMapper: ObjectMapper)` — `suspend fun fetch(source: NoticeSource, category: NoticeCategory, from: LocalDateTime, to: LocalDateTime): List<Notice>`
  - 테스트 헬퍼 `testProperties(...)`

- [ ] **Step 1: 재시도 테스트 작성**

`src/test/kotlin/com/narareminder/support/RetryTest.kt`:
```kotlin
package com.narareminder.support

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class RetryTest {

    private val delays = listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))

    @Test
    fun `실패하다 성공하면 결과를 돌려준다`() = runTest {
        var attempts = 0
        val result = retrying(delays) {
            attempts++
            if (attempts < 3) error("일시 오류")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `재시도를 모두 소진하면 마지막 예외를 던진다`() {
        var attempts = 0

        assertThatThrownBy {
            runBlocking {
                retrying(delays.map { Duration.ZERO }) {
                    attempts++
                    error("계속 실패 $attempts")
                }
            }
        }.hasMessage("계속 실패 4")
        assertThat(attempts).isEqualTo(4)
    }

    @Test
    fun `재시도 대상이 아니면 즉시 던진다`() {
        var attempts = 0

        assertThatThrownBy {
            runBlocking {
                retrying(delays, retryOn = { false }) {
                    attempts++
                    error("영구 오류")
                }
            }
        }.hasMessage("영구 오류")
        assertThat(attempts).isEqualTo(1)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.support.RetryTest'`
Expected: FAIL — `Unresolved reference: retrying`

- [ ] **Step 3: 재시도 구현**

`src/main/kotlin/com/narareminder/support/Retry.kt`:
```kotlin
package com.narareminder.support

import kotlinx.coroutines.delay
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

// delays 크기만큼 재시도한다 (최대 시도 횟수 = delays.size + 1)
suspend fun <T> retrying(
    delays: List<Duration>,
    retryOn: (Throwable) -> Boolean = { true },
    block: suspend () -> T,
): T {
    for (backoff in delays) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!retryOn(e)) throw e
            delay(backoff.toMillis())
        }
    }
    return block()
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.support.RetryTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 클라이언트 테스트 헬퍼·fixture·실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/config/TestProperties.kt`:
```kotlin
package com.narareminder.config

import com.narareminder.notice.NoticeCategory
import java.time.Duration

fun testProperties(
    baseUrl: String = "http://localhost",
    serviceKey: String = "test-key",
    pageSize: Int = 100,
    filter: NaraProperties.Filter = NaraProperties.Filter(categories = listOf(NoticeCategory.SERVICE)),
    bidAlertAfterFailures: Int = 6,
) = NaraProperties(
    serviceKey = serviceKey,
    mail = NaraProperties.Mail(from = "bot@example.com", to = listOf("me@example.com")),
    bid = NaraProperties.Job(
        overlap = Duration.ofMinutes(30),
        initialLookback = Duration.ofHours(1),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = bidAlertAfterFailures,
    ),
    preSpec = NaraProperties.Job(
        overlap = Duration.ofHours(1),
        initialLookback = Duration.ofHours(24),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = 1,
    ),
    api = NaraProperties.Api(baseUrl = baseUrl, pageSize = pageSize, timeout = Duration.ofSeconds(2)),
    filter = filter,
    retryDelays = listOf(Duration.ZERO, Duration.ZERO, Duration.ZERO),
)
```

`src/test/resources/fixtures/bid-servc.json`:
```json
{"response":{"header":{"resultCode":"00","resultMsg":"정상"},"body":{"items":[
  {"bidNtceNo":"R26BK00000001","bidNtceOrd":"000","bidNtceNm":"차세대 AI 플랫폼 구축 용역","dminsttNm":"조달청","presmptPrce":"150000000","bidClseDt":"2026-11-03 10:00:00","bidNtceDt":"2026-10-24 13:10:00","rgstDt":"2026-10-24 13:10:12","bidNtceDtlUrl":"https://www.g2b.go.kr/notice/R26BK00000001"},
  {"bidNtceNo":"R26BK00000002","bidNtceOrd":"001","bidNtceNm":"홈페이지 유지보수 & 운영","dminsttNm":"서울특별시","presmptPrce":"","bidClseDt":"","bidNtceDt":"2026-10-24 13:20:00","rgstDt":"2026/10/24","bidNtceDtlUrl":""}
],"numOfRows":100,"pageNo":1,"totalCount":2}}}
```

`src/test/resources/fixtures/pre-spec-servc.json`:
```json
{"response":{"header":{"resultCode":"00","resultMsg":"정상"},"body":{"items":[
  {"bfSpecRgstNo":"R26BD00000010","prdctClsfcNoNm":"클라우드 전환 컨설팅","rlDminsttNm":"한국지능정보사회진흥원","orderInsttNm":"조달청","asignBdgtAmt":"300000000.0","opninRgstClseDt":"2026-10-30 18:00:00","rgstDt":"2026-10-24 09:00:00"}
],"numOfRows":100,"pageNo":1,"totalCount":1}}}
```

`src/test/kotlin/com/narareminder/client/NaraApiClientTest.kt`:
```kotlin
package com.narareminder.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.narareminder.config.testProperties
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

class NaraApiClientTest {

    private val server = MockWebServer().apply { start() }
    private val from = LocalDateTime.of(2026, 10, 24, 13, 0)
    private val to = LocalDateTime.of(2026, 10, 24, 14, 0)

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(serviceKey: String = "test-key", pageSize: Int = 100) = NaraApiClient(
        WebClient.builder(),
        testProperties(baseUrl = server.url("/1230000").toString(), serviceKey = serviceKey, pageSize = pageSize),
        jacksonObjectMapper(),
    )

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")?.readText()
        ?: error("fixture 없음: $name")

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun page(totalCount: Int, vararg noticeNos: String) = """
        {"response":{"header":{"resultCode":"00","resultMsg":"정상"},"body":{"items":[
        ${noticeNos.joinToString(",") { """{"bidNtceNo":"$it","bidNtceOrd":"000","bidNtceNm":"공고 $it","rgstDt":"2026-10-24 13:10:00"}""" }}
        ],"numOfRows":2,"pageNo":1,"totalCount":$totalCount}}}
    """.trimIndent()

    @Test
    fun `입찰공고 목록을 조회해 Notice로 변환한다`() = runBlocking {
        server.enqueue(json(fixture("bid-servc.json")))

        val notices = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices).hasSize(2)
        with(notices[0]) {
            assertThat(noticeNo).isEqualTo("R26BK00000001")
            assertThat(title).isEqualTo("차세대 AI 플랫폼 구축 용역")
            assertThat(price).isEqualTo(150_000_000L)
            assertThat(agency).isEqualTo("조달청")
            assertThat(postedAt).isEqualTo(LocalDateTime.of(2026, 10, 24, 13, 10, 12))
            assertThat(deadlineAt).isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 0))
            assertThat(detailUrl).isEqualTo("https://www.g2b.go.kr/notice/R26BK00000001")
            assertThat(isRevision).isFalse()
        }
        assertThat(notices[1].isRevision).isTrue()

        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/1230000/ad/BidPublicInfoService/getBidPblancListInfoServc")
        assertThat(request.requestUrl?.queryParameter("inqryDiv")).isEqualTo("1")
        assertThat(request.requestUrl?.queryParameter("inqryBgnDt")).isEqualTo("202610241300")
        assertThat(request.requestUrl?.queryParameter("inqryEndDt")).isEqualTo("202610241400")
        assertThat(request.requestUrl?.queryParameter("type")).isEqualTo("json")
    }

    @Test
    fun `빈 값과 소수 가격과 이상한 날짜를 관대하게 매핑한다`() = runBlocking {
        server.enqueue(json(fixture("bid-servc.json")))
        server.enqueue(json(fixture("pre-spec-servc.json")))

        val bid = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)[1]
        val preSpec = client().fetch(NoticeSource.PRE_SPEC, NoticeCategory.SERVICE, from, to).single()

        assertThat(bid.price).isNull()
        assertThat(bid.deadlineAt).isNull()
        assertThat(bid.postedAt).isNull()
        assertThat(bid.detailUrl).isEqualTo("https://www.g2b.go.kr")
        assertThat(preSpec.price).isEqualTo(300_000_000L)
    }

    @Test
    fun `사전규격 목록을 사전규격 필드로 변환한다`() = runBlocking {
        server.enqueue(json(fixture("pre-spec-servc.json")))

        val notice = client().fetch(NoticeSource.PRE_SPEC, NoticeCategory.SERVICE, from, to).single()

        assertThat(notice.noticeNo).isEqualTo("R26BD00000010")
        assertThat(notice.noticeOrd).isEqualTo("0")
        assertThat(notice.title).isEqualTo("클라우드 전환 컨설팅")
        assertThat(notice.agency).isEqualTo("한국지능정보사회진흥원")
        assertThat(notice.deadlineAt).isEqualTo(LocalDateTime.of(2026, 10, 30, 18, 0))
        assertThat(server.takeRequest().requestUrl?.encodedPath)
            .isEqualTo("/1230000/ao/HrcspSsstndrdInfoService/getPublicPrcureThngInfoServc")
    }

    @Test
    fun `서비스키는 엄격하게 인코딩된다`() = runBlocking {
        server.enqueue(json(page(0)))

        client(serviceKey = "a+b/c=").fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(server.takeRequest().path).contains("serviceKey=a%2Bb%2Fc%3D")
    }

    @Test
    fun `전체 건수만큼 페이지를 순회한다`() = runBlocking {
        server.enqueue(json(page(3, "N1", "N2")))
        server.enqueue(json(page(3, "N3")))

        val notices = client(pageSize = 2).fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices.map { it.noticeNo }).containsExactly("N1", "N2", "N3")
        assertThat(server.takeRequest().requestUrl?.queryParameter("pageNo")).isEqualTo("1")
        assertThat(server.takeRequest().requestUrl?.queryParameter("pageNo")).isEqualTo("2")
    }

    @Test
    fun `결과코드 03은 0건으로 처리한다`() = runBlocking {
        server.enqueue(json("""{"response":{"header":{"resultCode":"03","resultMsg":"NO_DATA"}}}"""))

        assertThat(client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)).isEmpty()
    }

    @Test
    fun `비정상 결과코드는 재시도 없이 실패한다`() {
        server.enqueue(json("""{"response":{"header":{"resultCode":"07","resultMsg":"범위 초과"}}}"""))

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("07")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `JSON이 아닌 응답은 재시도 없이 실패한다`() {
        server.enqueue(
            MockResponse().setBody(
                "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>",
            ),
        )

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `5xx는 재시도 후 성공하면 결과를 돌려준다`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(json(page(1, "N1")))

        val notices = client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to)

        assertThat(notices).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `5xx가 계속되면 3회 재시도 후 실패한다`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

        assertThatThrownBy { runBlocking { client().fetch(NoticeSource.BID, NoticeCategory.SERVICE, from, to) } }
            .isInstanceOf(NaraApiException::class.java)
            .hasMessageContaining("500")
        assertThat(server.requestCount).isEqualTo(4)
    }
}
```

- [ ] **Step 6: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.client.NaraApiClientTest'`
Expected: FAIL — `Unresolved reference: NaraApiClient`

- [ ] **Step 7: 클라이언트 구현**

`src/main/kotlin/com/narareminder/client/NaraApiException.kt`:
```kotlin
package com.narareminder.client

class NaraApiException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

`src/main/kotlin/com/narareminder/client/NoticeMapper.kt`:
```kotlin
package com.narareminder.client

import com.fasterxml.jackson.databind.JsonNode
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 항목 하나의 형식 이상으로 업무구분 전체가 실패하지 않도록 필드 단위로 null 처리한다
internal object NoticeMapper {

    private const val G2B_URL = "https://www.g2b.go.kr"
    private val RESPONSE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

    fun toNotice(source: NoticeSource, category: NoticeCategory, item: JsonNode): Notice = when (source) {
        NoticeSource.BID -> Notice(
            source = source,
            noticeNo = item.text("bidNtceNo"),
            noticeOrd = item.text("bidNtceOrd"),
            title = item.text("bidNtceNm"),
            category = category,
            price = item.long("presmptPrce"),
            agency = item.textOrNull("dminsttNm"),
            postedAt = item.dateTime("rgstDt"),
            deadlineAt = item.dateTime("bidClseDt"),
            detailUrl = item.textOrNull("bidNtceDtlUrl") ?: G2B_URL,
        )
        NoticeSource.PRE_SPEC -> Notice(
            source = source,
            noticeNo = item.text("bfSpecRgstNo"),
            noticeOrd = "0",
            title = item.text("prdctClsfcNoNm"),
            category = category,
            price = item.long("asignBdgtAmt"),
            agency = item.textOrNull("rlDminsttNm"),
            postedAt = item.dateTime("rgstDt"),
            deadlineAt = item.dateTime("opninRgstClseDt"),
            detailUrl = G2B_URL,
        )
    }

    private fun JsonNode.text(field: String): String =
        path(field).let { if (it.isNull || it.isMissingNode) "" else it.asText().trim() }

    private fun JsonNode.textOrNull(field: String): String? = text(field).ifBlank { null }

    private fun JsonNode.long(field: String): Long? = textOrNull(field)?.toBigDecimalOrNull()?.toLong()

    private fun JsonNode.dateTime(field: String): LocalDateTime? =
        textOrNull(field)?.let { runCatching { LocalDateTime.parse(it, RESPONSE_DATE_TIME) }.getOrNull() }
}
```

`src/main/kotlin/com/narareminder/client/NaraApiClient.kt`:
```kotlin
package com.narareminder.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.narareminder.config.NaraProperties
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.support.retrying
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class NaraApiClient(
    webClientBuilder: WebClient.Builder,
    private val props: NaraProperties,
    private val objectMapper: ObjectMapper,
) {
    private val webClient = webClientBuilder
        .baseUrl(props.api.baseUrl)
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(props.api.timeout)))
        .build()

    suspend fun fetch(
        source: NoticeSource,
        category: NoticeCategory,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Notice> {
        val notices = mutableListOf<Notice>()
        var pageNo = 1
        while (true) {
            val currentPage = pageNo
            val page = retrying(props.retryDelays, retryOn = { it is NaraApiException && it.retryable }) {
                fetchPage(source, category, from, to, currentPage)
            }
            notices += page.items.map { NoticeMapper.toNotice(source, category, it) }
            if (currentPage * props.api.pageSize >= page.totalCount) return notices
            pageNo++
        }
    }

    private suspend fun fetchPage(
        source: NoticeSource,
        category: NoticeCategory,
        from: LocalDateTime,
        to: LocalDateTime,
        pageNo: Int,
    ): Page {
        val body = try {
            webClient.get()
                .uri { builder ->
                    builder.path("/${source.servicePath}/${source.operationPrefix}${category.operationSuffix}")
                        // 서비스키는 URI 변수로 넘겨야 +, /, = 가 엄격하게 인코딩된다
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", props.api.pageSize)
                        .queryParam("type", "json")
                        .queryParam("inqryDiv", INQUIRY_BY_REGISTERED_AT)
                        .queryParam("inqryBgnDt", from.format(QUERY_DATE_TIME))
                        .queryParam("inqryEndDt", to.format(QUERY_DATE_TIME))
                        .build(props.serviceKey)
                }
                .retrieve()
                .awaitBody<String>()
        } catch (e: WebClientResponseException) {
            throw NaraApiException("HTTP ${e.statusCode.value()}", retryable = e.statusCode.is5xxServerError, cause = e)
        } catch (e: WebClientRequestException) {
            throw NaraApiException("요청 실패: ${e.message}", retryable = true, cause = e)
        }
        return parsePage(body)
    }

    // 공공데이터포털은 인증·트래픽 오류를 HTTP 200 + XML 또는 비정상 결과코드로 돌려준다
    private fun parsePage(body: String): Page {
        val response = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?.path("response")
            ?.takeUnless { it.isMissingNode }
            ?: throw NaraApiException("JSON이 아닌 응답: ${body.take(300)}", retryable = false)

        val header = response.path("header")
        when (val code = header.path("resultCode").asText()) {
            RESULT_OK -> Unit
            RESULT_NO_DATA -> return Page(emptyList(), 0)
            else -> throw NaraApiException("결과코드 $code: ${header.path("resultMsg").asText()}", retryable = false)
        }

        val responseBody = response.path("body")
        val items = responseBody.path("items")
        val itemList = when {
            items.isArray -> items.toList()
            items.path("item").isArray -> items.path("item").toList()
            items.path("item").isObject -> listOf(items.path("item"))
            else -> emptyList()
        }
        return Page(itemList, responseBody.path("totalCount").asInt())
    }

    private data class Page(val items: List<JsonNode>, val totalCount: Int)

    companion object {
        private const val INQUIRY_BY_REGISTERED_AT = "1"
        private const val RESULT_OK = "00"
        private const val RESULT_NO_DATA = "03"
        private val QUERY_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
```

- [ ] **Step 8: 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.client.*' --tests 'com.narareminder.support.*'`
Expected: PASS (13 tests)

- [ ] **Step 9: 커밋**

```bash
git add src
git commit -m "feat(client): 나라장터 입찰공고/사전규격 API 클라이언트 추가

페이지 순회, 결과코드 검증, 5xx·연결 오류 백오프 재시도를 지원하고
서비스키 인코딩과 HTTP 200 XML 에러 응답을 명확히 처리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 발송 이력·작업 상태 저장소

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/kotlin/com/narareminder/store/NotifiedNotice.kt`
- Create: `src/main/kotlin/com/narareminder/store/NotifiedNoticeRepository.kt`
- Create: `src/main/kotlin/com/narareminder/job/JobName.kt`
- Create: `src/main/kotlin/com/narareminder/job/JobState.kt`
- Create: `src/main/kotlin/com/narareminder/job/JobStateRepository.kt`
- Create: `src/test/resources/application-test.yml`
- Create: `src/test/kotlin/com/narareminder/PostgresTestConfig.kt`
- Test: `src/test/kotlin/com/narareminder/store/PersistenceTest.kt`

**Interfaces:**
- Consumes: `Notice`, `NoticeSource` (Task 2)
- Produces:
  - `data class NotifiedNoticeId(source: NoticeSource, noticeNo: String, noticeOrd: String)`, `class NotifiedNotice(id: NotifiedNoticeId, title: String, notifiedAt: Instant)`, `fun Notice.notifiedId(): NotifiedNoticeId`
  - `interface NotifiedNoticeRepository : JpaRepository<NotifiedNotice, NotifiedNoticeId>`
  - `enum JobName(val source: NoticeSource) { BID_POLLING, PRE_SPEC_DAILY }`
  - `class JobState(jobName: JobName, lastSuccessAt: Instant? = null, consecutiveFailures: Int = 0, alertSent: Boolean = false)` — `recordSuccess(at: Instant)`, `recordFailure()`, `shouldAlert(threshold: Int): Boolean`, `markAlertSent()`
  - `interface JobStateRepository : JpaRepository<JobState, JobName>`
  - 테스트용 `PostgresTestConfig`, `application-test.yml` (Task 8에서 재사용)

- [ ] **Step 1: 테스트 설정과 실패하는 테스트 작성**

`src/test/resources/application-test.yml`:
```yaml
spring:
  mail:
    host: localhost
    port: 2525
    username: test
    password: test

nara:
  service-key: test-key
  mail:
    from: bot@example.com
    to: me@example.com
  scheduling:
    enabled: false
```

`src/test/kotlin/com/narareminder/PostgresTestConfig.kt`:
```kotlin
package com.narareminder

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
}
```

`src/test/kotlin/com/narareminder/store/PersistenceTest.kt`:
```kotlin
package com.narareminder.store

import com.narareminder.PostgresTestConfig
import com.narareminder.job.JobName
import com.narareminder.job.JobState
import com.narareminder.job.JobStateRepository
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class PersistenceTest @Autowired constructor(
    private val notifiedNoticeRepository: NotifiedNoticeRepository,
    private val jobStateRepository: JobStateRepository,
) {
    private val now = Instant.parse("2026-10-24T05:00:00Z")

    @Test
    fun `발송 이력을 공고 키로 조회한다`() {
        val sent = aNotice(noticeNo = "N1")
        notifiedNoticeRepository.saveAndFlush(NotifiedNotice(sent.notifiedId(), sent.title, now))

        val found = notifiedNoticeRepository.findAllById(
            listOf(sent.notifiedId(), aNotice(noticeNo = "N2").notifiedId()),
        )

        assertThat(found.map { it.id }).containsExactly(sent.notifiedId())
    }

    @Test
    fun `같은 공고의 다른 차수와 다른 출처는 별도로 저장된다`() {
        val original = aNotice(noticeNo = "N1", noticeOrd = "000")
        val revision = aNotice(noticeNo = "N1", noticeOrd = "001")
        val preSpec = aNotice(noticeNo = "N1", noticeOrd = "0", source = NoticeSource.PRE_SPEC)

        notifiedNoticeRepository.saveAllAndFlush(
            listOf(original, revision, preSpec).map { NotifiedNotice(it.notifiedId(), it.title, now) },
        )

        assertThat(notifiedNoticeRepository.count()).isEqualTo(3)
    }

    @Test
    fun `긴 공고명도 저장된다`() {
        val notice = aNotice(title = "가".repeat(2_000))

        notifiedNoticeRepository.saveAndFlush(NotifiedNotice(notice.notifiedId(), notice.title, now))

        assertThat(notifiedNoticeRepository.findByIdOrNull(notice.notifiedId())?.title).hasSize(2_000)
    }

    @Test
    fun `작업 상태를 저장하고 갱신한다`() {
        jobStateRepository.saveAndFlush(JobState(JobName.BID_POLLING).apply { recordFailure() })

        val state = jobStateRepository.findByIdOrNull(JobName.BID_POLLING)
        assertThat(state?.consecutiveFailures).isEqualTo(1)

        state?.recordSuccess(now)
        state?.let { jobStateRepository.saveAndFlush(it) }

        val updated = jobStateRepository.findByIdOrNull(JobName.BID_POLLING)
        assertThat(updated?.lastSuccessAt).isEqualTo(now)
        assertThat(updated?.consecutiveFailures).isZero()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.store.PersistenceTest'`
Expected: FAIL — `Unresolved reference: NotifiedNotice`, `JobState` 등

- [ ] **Step 3: 마이그레이션과 엔티티 구현**

`src/main/resources/db/migration/V1__init.sql`:
```sql
CREATE TABLE notified_notice (
    source       VARCHAR(16) NOT NULL,
    notice_no    VARCHAR(64) NOT NULL,
    notice_ord   VARCHAR(16) NOT NULL,
    title        TEXT        NOT NULL,
    notified_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, notice_no, notice_ord)
);

CREATE TABLE job_state (
    job_name             VARCHAR(32) PRIMARY KEY,
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    alert_sent           BOOLEAN     NOT NULL DEFAULT FALSE
);
```

`src/main/kotlin/com/narareminder/store/NotifiedNotice.kt`:
```kotlin
package com.narareminder.store

import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeSource
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Embeddable
data class NotifiedNoticeId(
    @Enumerated(EnumType.STRING)
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
) : Serializable

@Entity
@Table(name = "notified_notice")
class NotifiedNotice(
    @EmbeddedId
    val id: NotifiedNoticeId,
    @Column(columnDefinition = "text")
    val title: String,
    val notifiedAt: Instant,
)

fun Notice.notifiedId() = NotifiedNoticeId(source, noticeNo, noticeOrd)
```

`src/main/kotlin/com/narareminder/store/NotifiedNoticeRepository.kt`:
```kotlin
package com.narareminder.store

import org.springframework.data.jpa.repository.JpaRepository

interface NotifiedNoticeRepository : JpaRepository<NotifiedNotice, NotifiedNoticeId>
```

`src/main/kotlin/com/narareminder/job/JobName.kt`:
```kotlin
package com.narareminder.job

import com.narareminder.notice.NoticeSource

enum class JobName(val source: NoticeSource) {
    BID_POLLING(NoticeSource.BID),
    PRE_SPEC_DAILY(NoticeSource.PRE_SPEC),
}
```

`src/main/kotlin/com/narareminder/job/JobState.kt`:
```kotlin
package com.narareminder.job

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "job_state")
class JobState(
    @Id
    @Enumerated(EnumType.STRING)
    val jobName: JobName,
    var lastSuccessAt: Instant? = null,
    var consecutiveFailures: Int = 0,
    var alertSent: Boolean = false,
) {
    fun recordSuccess(at: Instant) {
        lastSuccessAt = at
        consecutiveFailures = 0
        alertSent = false
    }

    fun recordFailure() {
        consecutiveFailures++
    }

    // 같은 장애로 알림이 반복 발송되지 않도록 alertSent 로 1회만 허용한다
    fun shouldAlert(threshold: Int): Boolean = !alertSent && consecutiveFailures >= threshold

    fun markAlertSent() {
        alertSent = true
    }
}
```

`src/main/kotlin/com/narareminder/job/JobStateRepository.kt`:
```kotlin
package com.narareminder.job

import org.springframework.data.jpa.repository.JpaRepository

interface JobStateRepository : JpaRepository<JobState, JobName>
```

- [ ] **Step 4: 통과 확인 (Docker 데몬 실행 중이어야 함)**

Run: `./gradlew test --tests 'com.narareminder.store.PersistenceTest'`
Expected: PASS (4 tests). Flyway가 V1을 적용하고 `ddl-auto: validate`가 엔티티와 스키마 일치를 검증한다.

- [ ] **Step 5: 커밋**

```bash
git add src
git commit -m "feat(store): 발송 이력과 작업 상태 저장소 추가

(출처, 공고번호, 차수) 키로 중복 발송을 막고, 작업별 마지막 성공 시각과
연속 실패 횟수를 저장해 재조회와 에러 알림의 기준으로 사용

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 메일 발송

**Files:**
- Create: `src/main/kotlin/com/narareminder/mail/MailNotifier.kt`
- Test: `src/test/kotlin/com/narareminder/mail/MailNotifierTest.kt`

**Interfaces:**
- Consumes: `NaraProperties.mail`, `Notice`, `NoticeSource`, `JobName`, `KST`, `testProperties()`, `aNotice()`
- Produces: `@Component class MailNotifier(mailSender: JavaMailSender, props: NaraProperties)`
  - `fun sendNotices(source: NoticeSource, notices: List<Notice>, now: Instant)` — 0건이면 아무것도 하지 않음
  - `fun sendAlert(job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/mail/MailNotifierTest.kt`:
```kotlin
package com.narareminder.mail

import com.narareminder.config.testProperties
import com.narareminder.job.JobName
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSender
import java.time.Instant
import java.time.LocalDateTime
import java.util.Properties

class MailNotifierTest {

    private val sent = slot<MimeMessage>()
    private val mailSender = mockk<JavaMailSender> {
        every { createMimeMessage() } answers { MimeMessage(Session.getInstance(Properties())) }
        every { send(capture(sent)) } just Runs
    }
    private val notifier = MailNotifier(mailSender, testProperties())

    // KST 2026-10-24 14:05
    private val now = Instant.parse("2026-10-24T05:05:00Z")

    private fun html() = sent.captured.content as String

    @Test
    fun `입찰공고 메일 제목에 건수와 KST 시각이 들어가고 수신자가 설정된다`() {
        notifier.sendNotices(NoticeSource.BID, listOf(aNotice(noticeNo = "N1"), aNotice(noticeNo = "N2")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 새 입찰공고 2건 (10/24 14:05)")
        assertThat(sent.captured.from.single().toString()).isEqualTo("bot@example.com")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("me@example.com")
    }

    @Test
    fun `사전규격 메일 제목은 날짜만 표시한다`() {
        notifier.sendNotices(NoticeSource.PRE_SPEC, listOf(aNotice(source = NoticeSource.PRE_SPEC, noticeOrd = "0")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 사전규격 1건 (10/24)")
    }

    @Test
    fun `정정공고 표시와 가격 형식과 빈 값을 렌더링한다`() {
        notifier.sendNotices(
            NoticeSource.BID,
            listOf(
                aNotice(noticeNo = "N1", noticeOrd = "001", title = "AI 플랫폼", price = 150_000_000),
                aNotice(noticeNo = "N2", title = "유지보수", price = null, agency = null, deadlineAt = null),
            ),
            now,
        )

        assertThat(html())
            .contains("[정정] AI 플랫폼")
            .contains("150,000,000원")
            .contains("2026-11-03 10:00")
            .contains("https://www.g2b.go.kr/notice/N1")
            .contains("<td>-</td>")
    }

    @Test
    fun `공고명은 HTML 이스케이프된다`() {
        notifier.sendNotices(NoticeSource.BID, listOf(aNotice(title = "A&B <script>alert(1)</script>")), now)

        assertThat(html())
            .contains("A&amp;B &lt;script&gt;")
            .doesNotContain("<script>")
    }

    @Test
    fun `게시일 오름차순으로 정렬한다`() {
        notifier.sendNotices(
            NoticeSource.BID,
            listOf(
                aNotice(noticeNo = "LATE", title = "늦은 공고", postedAt = LocalDateTime.of(2026, 10, 24, 14, 0)),
                aNotice(noticeNo = "EARLY", title = "이른 공고", postedAt = LocalDateTime.of(2026, 10, 24, 13, 0)),
            ),
            now,
        )

        assertThat(html().indexOf("이른 공고")).isLessThan(html().indexOf("늦은 공고"))
    }

    @Test
    fun `0건이면 발송하지 않는다`() {
        notifier.sendNotices(NoticeSource.BID, emptyList(), now)

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }

    @Test
    fun `에러 알림 메일에 작업명과 연속 실패 횟수와 오류가 들어간다`() {
        notifier.sendAlert(JobName.BID_POLLING, 6, Instant.parse("2026-10-24T04:30:00Z"), "용역 조회 실패: 결과코드 30 <키 오류>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 입찰공고 수집 실패 (6회 연속)")
        assertThat(html())
            .contains("2026-10-24 13:30")
            .contains("결과코드 30 &lt;키 오류&gt;")
    }

    @Test
    fun `마지막 성공 시각이 없으면 없음으로 표시한다`() {
        notifier.sendAlert(JobName.PRE_SPEC_DAILY, 1, null, "오류")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 사전규격 수집 실패 (1회 연속)")
        assertThat(html()).contains("없음")
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.mail.MailNotifierTest'`
Expected: FAIL — `Unresolved reference: MailNotifier`

- [ ] **Step 3: 구현**

`src/main/kotlin/com/narareminder/mail/MailNotifier.kt`:
```kotlin
package com.narareminder.mail

import com.narareminder.config.NaraProperties
import com.narareminder.job.JobName
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeSource
import com.narareminder.support.KST
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class MailNotifier(
    private val mailSender: JavaMailSender,
    private val props: NaraProperties,
) {
    fun sendNotices(source: NoticeSource, notices: List<Notice>, now: Instant) {
        if (notices.isEmpty()) return
        val subject = when (source) {
            NoticeSource.BID -> "[나라장터] 새 입찰공고 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE_TIME)})"
            NoticeSource.PRE_SPEC -> "[나라장터] 사전규격 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE)})"
        }
        send(subject, noticeTable(notices))
    }

    fun sendAlert(job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String) {
        val subject = "[나라장터 알림 서버] ${job.source.label} 수집 실패 (${consecutiveFailures}회 연속)"
        val html = """
            <p>작업: ${job.source.label}</p>
            <p>연속 실패: ${consecutiveFailures}회</p>
            <p>마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}</p>
            <pre>${escape(error)}</pre>
        """.trimIndent()
        send(subject, html)
    }

    private fun noticeTable(notices: List<Notice>): String {
        val rows = notices.sortedBy { it.postedAt }.joinToString("\n") { notice ->
            val title = (if (notice.isRevision) "[정정] " else "") + notice.title
            """
            <tr>
              <td><a href="${escape(notice.detailUrl)}">${escape(title)}</a><br><small>${escape(notice.noticeNo)}</small></td>
              <td>${notice.category.label}</td>
              <td>${escape(notice.agency ?: "-")}</td>
              <td>${notice.price?.let { "%,d원".format(it) } ?: "-"}</td>
              <td>${notice.deadlineAt?.format(DISPLAY_DATE_TIME) ?: "-"}</td>
            </tr>
            """.trimIndent()
        }
        return """
            <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>공고명</th><th>업무구분</th><th>수요기관</th><th>가격</th><th>마감일시</th></tr>
            $rows
            </table>
        """.trimIndent()
    }

    private fun send(subject: String, html: String) {
        val message = mailSender.createMimeMessage()
        MimeMessageHelper(message, false, "UTF-8").apply {
            setFrom(props.mail.from)
            setTo(props.mail.to.toTypedArray())
            setSubject(subject)
            setText(html, true)
        }
        mailSender.send(message)
    }

    private fun escape(text: String): String = HtmlUtils.htmlEscape(text, "UTF-8")

    companion object {
        private val SUBJECT_DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        private val SUBJECT_DATE = DateTimeFormatter.ofPattern("MM/dd")
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.mail.MailNotifierTest'`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋**

```bash
git add src
git commit -m "feat(mail): 공고 목록 메일과 에러 알림 메일 발송 추가

새 공고를 HTML 표 한 통으로 묶어 보내고, 수집 반복 실패 시 작업명과 오류를 담은 알림을 발송

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 조회 구간 계산과 수집 파이프라인

**Files:**
- Create: `src/main/kotlin/com/narareminder/job/QueryWindow.kt`
- Create: `src/main/kotlin/com/narareminder/job/NoticePipeline.kt`
- Test: `src/test/kotlin/com/narareminder/job/QueryWindowTest.kt`, `src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt`

**Interfaces:**
- Consumes: `NaraApiClient.fetch`, `NaraApiException` (Task 4), `matches` (Task 3), `NotifiedNoticeRepository`, `NotifiedNotice`, `notifiedId()`, `JobName`, `JobState`, `JobStateRepository` (Task 5), `MailNotifier` (Task 6), `retrying`, `KST`
- Produces:
  - `data class QueryWindow(from: Instant, to: Instant)` + `QueryWindow.of(lastSuccessAt: Instant?, now: Instant, settings: NaraProperties.Job)`
  - `@Component class NoticePipeline(client, notifiedNoticeRepository, jobStateRepository, mailNotifier, props, clock: Clock)` — `suspend fun run(job: JobName)`

- [ ] **Step 1: 조회 구간 테스트 작성**

`src/test/kotlin/com/narareminder/job/QueryWindowTest.kt`:
```kotlin
package com.narareminder.job

import com.narareminder.config.NaraProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class QueryWindowTest {

    private val settings = NaraProperties.Job(
        overlap = Duration.ofMinutes(30),
        initialLookback = Duration.ofHours(1),
        maxLookback = Duration.ofDays(7),
        alertAfterFailures = 6,
    )
    private val now = Instant.parse("2026-10-24T05:00:00Z")

    @Test
    fun `최초 실행은 initialLookback 만큼 거슬러 조회한다`() {
        assertThat(QueryWindow.of(null, now, settings))
            .isEqualTo(QueryWindow(now.minus(Duration.ofHours(1)), now))
    }

    @Test
    fun `정상 실행은 마지막 성공 시각에서 overlap 만큼 겹쳐 조회한다`() {
        val lastSuccess = now.minus(Duration.ofMinutes(5))

        assertThat(QueryWindow.of(lastSuccess, now, settings))
            .isEqualTo(QueryWindow(lastSuccess.minus(Duration.ofMinutes(30)), now))
    }

    @Test
    fun `장기 중단 후에는 maxLookback 까지만 조회한다`() {
        val lastSuccess = now.minus(Duration.ofDays(30))

        assertThat(QueryWindow.of(lastSuccess, now, settings))
            .isEqualTo(QueryWindow(now.minus(Duration.ofDays(7)), now))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.job.QueryWindowTest'`
Expected: FAIL — `Unresolved reference: QueryWindow`

- [ ] **Step 3: 조회 구간 구현**

`src/main/kotlin/com/narareminder/job/QueryWindow.kt`:
```kotlin
package com.narareminder.job

import com.narareminder.config.NaraProperties
import java.time.Instant

data class QueryWindow(val from: Instant, val to: Instant) {

    companion object {
        // API 반영 지연을 흡수하도록 overlap 만큼 겹쳐 조회하고, 중복은 발송 이력으로 거른다
        fun of(lastSuccessAt: Instant?, now: Instant, settings: NaraProperties.Job): QueryWindow {
            val from = lastSuccessAt
                ?.let { maxOf(it.minus(settings.overlap), now.minus(settings.maxLookback)) }
                ?: now.minus(settings.initialLookback)
            return QueryWindow(from, now)
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.job.QueryWindowTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 파이프라인 실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt`:
```kotlin
package com.narareminder.job

import com.narareminder.client.NaraApiClient
import com.narareminder.client.NaraApiException
import com.narareminder.config.NaraProperties
import com.narareminder.config.testProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.Notice
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
import com.narareminder.store.NotifiedNotice
import com.narareminder.store.NotifiedNoticeRepository
import com.narareminder.store.notifiedId
import com.narareminder.support.KST
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

class NoticePipelineTest {

    // KST 2026-10-24 14:00
    private val now = Instant.parse("2026-10-24T05:00:00Z")
    private val client = mockk<NaraApiClient>()
    private val notifiedNoticeRepository = mockk<NotifiedNoticeRepository>()
    private val jobStateRepository = mockk<JobStateRepository>()
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val state = JobState(JobName.BID_POLLING)

    private val aiFilter = NaraProperties.Filter(keywords = listOf("AI"), categories = listOf(NoticeCategory.SERVICE))

    private fun pipeline(props: NaraProperties = testProperties(filter = aiFilter)) =
        NoticePipeline(client, notifiedNoticeRepository, jobStateRepository, mailNotifier, props, Clock.fixed(now, KST))

    private fun runBid(props: NaraProperties = testProperties(filter = aiFilter)) =
        runBlocking { pipeline(props).run(JobName.BID_POLLING) }

    private fun sentNoticeNos(): List<String> {
        val captured = mutableListOf<List<Notice>>()
        verify { mailNotifier.sendNotices(NoticeSource.BID, capture(captured), now) }
        return captured.last().map { it.noticeNo }
    }

    @BeforeEach
    fun setUp() {
        every { jobStateRepository.findById(JobName.BID_POLLING) } returns Optional.of(state)
        every { jobStateRepository.save(any<JobState>()) } answers { firstArg() }
        every { notifiedNoticeRepository.findAllById(any()) } returns emptyList()
        every { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) } answers { firstArg() }
    }

    @Test
    fun `최초 실행은 initialLookback 구간을 KST로 조회한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()

        runBid()

        coVerify {
            client.fetch(
                NoticeSource.BID,
                NoticeCategory.SERVICE,
                LocalDateTime.of(2026, 10, 24, 13, 0),
                LocalDateTime.of(2026, 10, 24, 14, 0),
            )
        }
    }

    @Test
    fun `조건에 맞는 새 공고만 메일로 보내고 기록한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(
            aNotice(noticeNo = "N1", title = "AI 플랫폼"),
            aNotice(noticeNo = "N2", title = "청사 청소"),
        )

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N1")
        verify { notifiedNoticeRepository.saveAll(match<List<NotifiedNotice>> { it.single().id.noticeNo == "N1" }) }
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `맞는 공고가 없으면 메일 없이 성공으로 기록한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(aNotice(title = "청사 청소"))

        runBid()

        verify(exactly = 0) { mailNotifier.sendNotices(any(), any(), any()) }
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `이미 보낸 공고는 제외한다`() {
        val sent = aNotice(noticeNo = "N1", title = "AI 플랫폼")
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(sent, aNotice(noticeNo = "N3", title = "AI 분석"))
        every { notifiedNoticeRepository.findAllById(any()) } returns listOf(NotifiedNotice(sent.notifiedId(), sent.title, now))

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N3")
    }

    @Test
    fun `같은 실행에서 중복 조회된 공고는 한 번만 보낸다`() {
        val notice = aNotice(noticeNo = "N1", title = "AI 플랫폼")
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(notice, notice.copy())

        runBid()

        assertThat(sentNoticeNos()).containsExactly("N1")
    }

    @Test
    fun `메일 발송이 실패하면 기록하지 않고 실패로 처리한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } returns listOf(aNotice(title = "AI 플랫폼"))
        every { mailNotifier.sendNotices(any(), any(), any()) } throws RuntimeException("SMTP 오류")

        runBid()

        verify(exactly = 4) { mailNotifier.sendNotices(any(), any(), any()) }
        verify(exactly = 0) { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) }
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `일부 업무구분만 실패하면 성공분은 보내고 실행은 실패로 처리한다`() {
        val props = testProperties(
            filter = aiFilter.copy(categories = listOf(NoticeCategory.SERVICE, NoticeCategory.GOODS)),
        )
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns
            listOf(aNotice(noticeNo = "N1", title = "AI 플랫폼"))
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.GOODS, any(), any()) } throws
            NaraApiException("HTTP 500", retryable = true)

        runBid(props)

        assertThat(sentNoticeNos()).containsExactly("N1")
        verify { notifiedNoticeRepository.saveAll(any<List<NotifiedNotice>>()) }
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `연속 실패가 임계치에 도달하면 에러 알림을 한 번만 보낸다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } throws NaraApiException("결과코드 30: 키 오류", retryable = false)
        val props = testProperties(filter = aiFilter, bidAlertAfterFailures = 2)

        runBid(props)
        verify(exactly = 0) { mailNotifier.sendAlert(any(), any(), any(), any()) }

        runBid(props)
        runBid(props)

        verify(exactly = 1) {
            mailNotifier.sendAlert(JobName.BID_POLLING, 2, null, match { it.contains("결과코드 30") })
        }
        assertThat(state.alertSent).isTrue()
        assertThat(state.consecutiveFailures).isEqualTo(3)
    }

    @Test
    fun `에러 알림 발송이 실패하면 다음 실패 때 다시 시도한다`() {
        coEvery { client.fetch(any(), any(), any(), any()) } throws NaraApiException("HTTP 500", retryable = true)
        every { mailNotifier.sendAlert(any(), any(), any(), any()) } throws RuntimeException("SMTP 오류")
        val props = testProperties(filter = aiFilter, bidAlertAfterFailures = 1)

        runBid(props)

        assertThat(state.alertSent).isFalse()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `성공하면 실패 횟수와 알림 상태를 초기화한다`() {
        state.consecutiveFailures = 7
        state.alertSent = true
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()

        runBid()

        assertThat(state.consecutiveFailures).isZero()
        assertThat(state.alertSent).isFalse()
        verify { jobStateRepository.save(state) }
    }
}
```

- [ ] **Step 6: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.job.NoticePipelineTest'`
Expected: FAIL — `Unresolved reference: NoticePipeline`

- [ ] **Step 7: 파이프라인 구현**

`src/main/kotlin/com/narareminder/job/NoticePipeline.kt`:
```kotlin
package com.narareminder.job

import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.Notice
import com.narareminder.notice.matches
import com.narareminder.store.NotifiedNotice
import com.narareminder.store.NotifiedNoticeRepository
import com.narareminder.store.notifiedId
import com.narareminder.support.KST
import com.narareminder.support.retrying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

@Component
class NoticePipeline(
    private val client: NaraApiClient,
    private val notifiedNoticeRepository: NotifiedNoticeRepository,
    private val jobStateRepository: JobStateRepository,
    private val mailNotifier: MailNotifier,
    private val props: NaraProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실패하면 lastSuccessAt 을 갱신하지 않아 다음 배치가 같은 구간을 다시 조회한다
    suspend fun run(job: JobName) {
        // JPA·SMTP 블로킹 호출이 WebClient 이벤트 루프 스레드에서 실행되지 않도록 IO 디스패처로 옮긴다
        withContext(Dispatchers.IO) {
            val settings = props.settingsOf(job)
            val now = clock.instant()
            val state = jobStateRepository.findByIdOrNull(job) ?: JobState(job)
            val window = QueryWindow.of(state.lastSuccessAt, now, settings)
            val errors = mutableListOf<String>()

            val fetched = props.filter.categories.flatMap { category ->
                try {
                    client.fetch(job.source, category, window.from.toKst(), window.to.toKst())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${category.label} 조회 실패: ${e.message}"
                    emptyList()
                }
            }

            val newNotices = excludeNotified(fetched.filter { props.filter.matches(it) })
            if (newNotices.isNotEmpty()) {
                try {
                    retrying(props.retryDelays) { mailNotifier.sendNotices(job.source, newNotices, now) }
                    notifiedNoticeRepository.saveAll(newNotices.map { NotifiedNotice(it.notifiedId(), it.title, now) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "메일 발송/기록 실패: ${e.message}"
                }
            }

            if (errors.isEmpty()) {
                state.recordSuccess(now)
            } else {
                state.recordFailure()
                log.error("[{}] 수집 실패 ({}회 연속): {}", job, state.consecutiveFailures, errors)
                alertIfNeeded(job, state, settings, errors)
            }
            jobStateRepository.save(state)
        }
    }

    private fun excludeNotified(notices: List<Notice>): List<Notice> {
        val unique = notices.distinctBy { it.notifiedId() }
        val notified = notifiedNoticeRepository.findAllById(unique.map { it.notifiedId() }).map { it.id }.toSet()
        return unique.filterNot { it.notifiedId() in notified }
    }

    private fun alertIfNeeded(job: JobName, state: JobState, settings: NaraProperties.Job, errors: List<String>) {
        if (!state.shouldAlert(settings.alertAfterFailures)) return
        try {
            mailNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, errors.joinToString("\n"))
            state.markAlertSent()
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 메일 발송 실패", job, e)
        }
    }

    private fun NaraProperties.settingsOf(job: JobName): NaraProperties.Job = when (job) {
        JobName.BID_POLLING -> bid
        JobName.PRE_SPEC_DAILY -> preSpec
    }

    private fun Instant.toKst(): LocalDateTime = atZone(KST).toLocalDateTime()
}
```

- [ ] **Step 8: 통과 확인**

Run: `./gradlew test --tests 'com.narareminder.job.*'`
Expected: PASS (13 tests)

- [ ] **Step 9: 커밋**

```bash
git add src
git commit -m "feat(job): 조회 구간 계산과 공고 수집 파이프라인 추가

조회→필터→중복 제외→메일 발송→이력 기록을 수행하고, 실패 시 마지막 성공 시각을
유지해 다음 배치에서 재조회하며 연속 실패 임계치 도달 시 에러 알림을 1회 발송

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 스케줄러 연결과 컨텍스트 스모크 테스트

**Files:**
- Create: `src/main/kotlin/com/narareminder/config/SchedulingConfig.kt`
- Create: `src/main/kotlin/com/narareminder/job/NoticeJobs.kt`
- Test: `src/test/kotlin/com/narareminder/job/NoticeJobsTest.kt`, `src/test/kotlin/com/narareminder/NaraReminderApplicationTest.kt`

**Interfaces:**
- Consumes: `NoticePipeline.run(JobName)` (Task 7), `PostgresTestConfig`, `application-test.yml` (Task 5)
- Produces: `@Component class NoticeJobs(pipeline: NoticePipeline)` — `suspend fun pollBids()`, `suspend fun collectPreSpecs()`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/narareminder/job/NoticeJobsTest.kt`:
```kotlin
package com.narareminder.job

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NoticeJobsTest {

    private val pipeline = mockk<NoticePipeline> {
        coEvery { run(any()) } returns Unit
    }
    private val jobs = NoticeJobs(pipeline)

    @Test
    fun `입찰공고 폴링은 BID_POLLING 작업을 실행한다`() = runBlocking {
        jobs.pollBids()

        coVerify { pipeline.run(JobName.BID_POLLING) }
    }

    @Test
    fun `사전규격 수집은 PRE_SPEC_DAILY 작업을 실행한다`() = runBlocking {
        jobs.collectPreSpecs()

        coVerify { pipeline.run(JobName.PRE_SPEC_DAILY) }
    }
}
```

`src/test/kotlin/com/narareminder/NaraReminderApplicationTest.kt`:
```kotlin
package com.narareminder

import com.narareminder.job.NoticeJobs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class NaraReminderApplicationTest @Autowired constructor(
    private val noticeJobs: NoticeJobs,
) {
    @Test
    fun `애플리케이션 컨텍스트가 설정·DB 마이그레이션과 함께 뜬다`() {
        assertThat(noticeJobs).isNotNull()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.narareminder.job.NoticeJobsTest' --tests 'com.narareminder.NaraReminderApplicationTest'`
Expected: FAIL — `Unresolved reference: NoticeJobs`

- [ ] **Step 3: 구현**

`src/main/kotlin/com/narareminder/config/SchedulingConfig.kt`:
```kotlin
package com.narareminder.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// 테스트에서 스케줄러가 실제 API를 호출하지 않도록 끌 수 있게 한다
@Configuration
@EnableScheduling
@ConditionalOnProperty("nara.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig
```

`src/main/kotlin/com/narareminder/job/NoticeJobs.kt`:
```kotlin
package com.narareminder.job

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NoticeJobs(private val pipeline: NoticePipeline) {

    @Scheduled(fixedDelayString = "\${nara.bid.poll-interval}", initialDelayString = "PT10S")
    suspend fun pollBids() {
        pipeline.run(JobName.BID_POLLING)
    }

    @Scheduled(cron = "\${nara.pre-spec.cron}", zone = "Asia/Seoul")
    suspend fun collectPreSpecs() {
        pipeline.run(JobName.PRE_SPEC_DAILY)
    }
}
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS (전체 52 tests, 실패 0)

- [ ] **Step 5: 커밋**

```bash
git add src
git commit -m "feat(job): 입찰공고 5분 폴링과 사전규격 일일 수집 스케줄 등록

입찰공고는 이전 실행 종료 5분 후, 사전규격은 매일 08:00 KST에 파이프라인을 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Docker Compose 실행 환경과 README

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `.env.example`, `config/application.yml`, `README.md`

**Interfaces:**
- Consumes: 완성된 애플리케이션 (`./gradlew bootJar`)
- Produces: `docker compose up -d`로 실행되는 서비스

- [ ] **Step 1: 컨테이너 파일 작성**

`Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Seoul
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
```

`.dockerignore`:
```
.git
.gradle
.kotlin
build
.env
.idea
```

`docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: nara
      POSTGRES_USER: ${DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      TZ: Asia/Seoul
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d nara"]
      interval: 5s
      timeout: 3s
      retries: 10
    restart: unless-stopped

  app:
    build: .
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/nara
      TZ: Asia/Seoul
    volumes:
      # 필터 설정은 재빌드 없이 수정 후 `docker compose restart app` 으로 반영한다
      - ./config/application.yml:/app/config/application.yml:ro
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

volumes:
  pgdata:
```

`.env.example`:
```
# 공공데이터포털 마이페이지의 "Decoding" 인증키 (Encoding 키를 넣으면 이중 인코딩되어 인증 실패)
NARA_SERVICE_KEY=

# SMTP (Gmail: smtp.gmail.com / 587 / 앱 비밀번호)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=

MAIL_FROM=
# 여러 명이면 쉼표로 구분
MAIL_TO=

DB_USERNAME=nara
DB_PASSWORD=change-me
```

`config/application.yml`:
```yaml
nara:
  filter:
    keywords: [소프트웨어, 유지보수, AI]
    exclude-keywords: []
    categories: [SERVICE, GOODS]
    price-min: 50000000
    agencies: []
```

- [ ] **Step 2: 이미지 빌드 확인**

Run: `docker compose build app`
Expected: 빌드 성공 (`naming to docker.io/library/...-app` 출력)

- [ ] **Step 3: README 작성**

`README.md`:
````markdown
# nara-reminder

나라장터 입찰공고(5분 주기)와 사전규격(매일 08:00)을 수집해 조건에 맞는 새 공고를 이메일로 보내는 서버.

## 준비

1. [공공데이터포털](https://www.data.go.kr)에서 다음 두 API를 활용신청한다.
   - 조달청_나라장터 입찰공고정보서비스
   - 조달청_나라장터 사전규격정보서비스
2. 마이페이지에서 **Decoding** 인증키를 복사한다.
3. Gmail을 쓰는 경우 2단계 인증을 켜고 [앱 비밀번호](https://myaccount.google.com/apppasswords)를 발급한다.

## 실행

```bash
cp .env.example .env   # 값 채우기
vi config/application.yml   # 필터 조건 수정
docker compose up -d --build
docker compose logs -f app
```

필터를 바꾼 뒤에는 `docker compose restart app` 으로 반영한다.

## 필터 규칙

- 조건끼리는 AND, 목록 안의 값끼리는 OR. 비어 있는 조건은 검사하지 않는다.
- `keywords` / `exclude-keywords`: 공고명 부분일치(대소문자 무시)
- `categories`: `GOODS`(물품) `SERVICE`(용역) `CONSTRUCTION`(공사) `FOREIGN`(외자)
- `price-min` / `price-max`: 입찰공고는 추정가격, 사전규격은 배정예산 기준(이상/이하). 가격이 없는 공고는 통과
- `agencies`: 입찰공고는 수요기관, 사전규격은 실수요기관 부분일치

## 운영 참고

- 개발계정 일일 호출 한도는 오퍼레이션별 1,000회다. 업무구분 하나당 하루 288회(5분 주기) + 페이지 수만큼 호출하므로, 공고가 많아 한도를 넘으면 활용사례 등록 후 운영계정 트래픽 증설을 신청한다.
- 입찰공고는 6회(30분), 사전규격은 1회 연속 실패하면 에러 알림 메일이 1회 발송된다. 복구되면 초기화된다.
- 서버가 멈췄다 재시작되면 마지막 성공 시각부터(최대 7일) 이어서 조회한다.

## 개발

```bash
./gradlew test   # Docker 데몬 필요 (Testcontainers)
```
````

- [ ] **Step 4: 실제 기동 확인 (서비스키·SMTP 값이 있을 때)**

```bash
cp .env.example .env   # 실제 값 입력
docker compose up -d --build
docker compose logs -f app
```

Expected: `Started NaraReminderApplication` 로그 후 10초 뒤 첫 입찰공고 수집이 실행되고 ERROR 로그가 없다. 조건에 맞는 공고가 있으면 메일이 도착한다. 확인 후 `docker compose down` (볼륨 유지).
값이 없으면 이 Step은 건너뛰고 사용자에게 수동 확인을 요청한다고 보고한다.

- [ ] **Step 5: 커밋**

```bash
git add Dockerfile .dockerignore docker-compose.yml .env.example config/application.yml README.md
git commit -m "chore(deploy): Docker Compose 실행 환경과 README 추가

앱과 PostgreSQL을 Compose로 실행하고, 필터 설정은 마운트한 config/application.yml로
재빌드 없이 수정할 수 있게 구성

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
