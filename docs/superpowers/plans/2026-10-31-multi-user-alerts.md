# 다중 사용자 알림 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 팀원이 초대 코드로 가입해 키워드·수요기관마다 발송 주기(cron)를 정해 알림을 받도록 하고, 서버를 수집·발송(worker)과 화면 API(api)로 나눈다.

**Architecture:** Gradle 멀티 모듈(`core`/`api`/`worker`). worker 는 전역 수집 후 `notice` 에 저장하고 사용자별 `delivery` 대기함에 매칭, 1분마다 `next(matched_at) ≤ now` 로 발송을 판정한다. api 는 Spring Security 세션 로그인과 REST 를 맡고 인증 메일은 `outbox_mail` 에 넣는다. 두 프로세스는 PostgreSQL 로만 연결된다.

**Tech Stack:** Kotlin 2.2.20, Spring Boot 3.5.6 (MVC, Security, Data JPA, Mail, WebFlux client), JDK 21, Gradle 8.14.3, PostgreSQL 16 + Flyway, React 18.3.1 + TypeScript 5.6.3 + Vite 5.4.21 + react-router-dom 6.30.6, Vitest 2.1.9 + RTL, docker compose + Caddy.

**Spec:** `docs/superpowers/specs/2026-10-30-multi-user-alerts-design.md`

## Global Constraints

- 설명·주석은 한국어, 식별자는 영어. 주석은 기존 코드처럼 "왜"가 필요한 곳에만 짧게.
- Kotlin: `!!` 금지(`requireNotNull`/`checkNotNull`/`?:` 사용). TS: `any` 금지(`unknown` 사용), named export 만.
- 테스트: 백엔드 JUnit5 + MockK + Testcontainers PostgreSQL(`PostgresTestConfig`), 프론트 Vitest + React Testing Library.
- 커밋: Conventional Commits `<type>(<scope>): <한국어 제목>`, 본문은 한국어로 이유, 마지막 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 요청하지 않은 리팩터링·기능·방어 코드 금지. 스펙 "범위 밖" 항목(선택 배포, 개인 디스코드, 사용자 비활성화 화면, 세션 영속화, 사용자 목록, 오늘 발송량 표시, Gmail 외 발송 수단)은 만들지 않는다.
- 공개 저장소다. 실제 메일 주소·비밀값을 코드·테스트·문서에 쓰지 않는다(예시는 `@example.com`).
- 발송 주기 cron: Spring `CronExpression` 6필드, Asia/Seoul. 즉시 = `0 */5 * * * *`. 발송 간격 5분 미만은 400 `INVALID_CRON`.
- 보관 기간 90일. 로그인 5회 연속 실패 시 15분 잠금. 비밀번호 8~72자. 확인 링크 24시간, 재설정 링크 30분(1회). 발송·outbox 3회 실패 시 FAILED + 디스코드. 하루 발송 400통 초과 시 디스코드 경고(하루 1번).
- JVM 힙: api `-Xmx160m`, worker `-Xmx192m`.
- 기존 `filter_term`, `filter_setting`, `notified_notice` 테이블은 지우지 않는다(마이그레이션으로 DROP 하지 않음).

## Review Focus

1. **이메일 대소문자** — `New@Example.com` 으로 가입한 사람이 `new@example.com` 으로 로그인·비밀번호 재설정이 되어야 한다 (Task 9 `SignupApiTest`).
2. **정정공고** — 이미 받은 공고의 차수가 올라(`000`→`001`) 다시 들어오면 새 공고로 매칭되어 다시 발송되어야 한다 (Task 5 `NoticeCollectorTest`).
3. **한 사람의 SMTP 실패** — 한 사용자 메일이 실패해도 같은 판정의 다른 사용자는 발송되어야 한다 (Task 6 `DeliveryDispatcherTest`).
4. **cron 직접 편집** — 화면에서 `0 * * * * *` 처럼 촘촘한 식을 직접 넣으면 저장이 400 으로 거절되고 이유가 보여야 한다 (Task 10 `ItemApiTest`, Task 13 `CadenceEditor.test`).
5. **전환 직후 중복 메일** — V5 이관 뒤 조회 구간이 겹쳐 이미 보낸 공고가 다시 들어와도 매칭되지 않아야 한다 (Task 3 `LegacyFilterMigrationTest` 의 `notice` 이관 + Task 5 "이미 저장된 공고" 테스트).

## 스펙과 다르게 정한 것 (Rulings)

- Ruling: `notice` 의 키 컬럼은 스펙의 `bid_no/ord` 대신 기존 코드 이름 `notice_no/notice_ord` 를 쓰고 `category`, `posted_at`, `deadline_at`, `detail_url` 을 그대로 둔다 — 기존 `Notice` 와 메일 형식을 그대로 쓰기 위함 — 틀려도 이름만 다름.
- Ruling: `.env` 를 `api.env`/`worker.env` 로 나누지 않고, `.env` 하나를 유지하되 compose 의 서비스별 `environment:` 에 필요한 변수만 넘긴다 — compose 변수 치환에 `.env` 가 어차피 필요하고, api 컨테이너에 비밀값이 안 들어간다는 스펙 목표는 같다 — 틀리면 compose 만 고치면 됨.
- Ruling: `LEGACY_OWNER_EMAIL` 은 Flyway placeholder `legacyowner` 로 V5 에 전달한다 — 테스트에서 환경변수 없이 값을 줄 수 있다.
- Ruling: 수집은 사용자별 업무구분과 무관하게 4개 업무구분을 모두 조회한다 — 업무구분이 사용자 공통 조건으로 바뀌었기 때문.
- Ruling: `job_state` 갱신은 공고 저장·매칭 트랜잭션과 따로 한다 — 갱신이 실패해도 다음 실행이 같은 구간을 다시 조회하고 `notice` 키로 걸러지므로 중복이 생기지 않는다.
- Ruling: 배치 장애 알림 메일은 `MAIL_TO` 대신 관리자(`is_admin`) 이메일로 보낸다 — `MAIL_TO` 가 없어지므로.
- Ruling: `user_filter.exclude_keywords` 는 `text[]` 대신 줄바꿈 구분 `text` 로 저장한다 — 기존 `categories` CSV 와 같은 방식이고 Hibernate 배열 매핑 검증 위험을 피한다. 값에 줄바꿈은 허용하지 않는다.
- Ruling: worker 헬스체크는 "기동 후 running 유지", api 는 compose healthcheck(8080 포트 열림)로 한다.
- Ruling: worker 에는 `APP_BASE_URL` 을 주지 않는다 — 링크가 들어가는 인증 메일 본문은 api 가 만들어 outbox 에 넣으므로 worker 는 쓰지 않는다.
- Ruling: `job_run` 의 건수 열 의미 — 수집: `fetched`=조회, `matched`=새 공고, `sent`=매칭(발송 대기 생성) / 발송(`DELIVERY`): `matched`=보낸 공고 수, `sent`=보낸 메일 수. 화면은 작업별로 다르게 표시한다.

## 파일 구조 (최종)

```
settings.gradle.kts, build.gradle.kts          루트: 모듈 포함, 공통 Kotlin/Test 설정
core/   build.gradle.kts
  src/main/kotlin/com/narareminder/
    support/Time.kt, ClockConfig.kt            KST, Clock 빈
    web/ApiException.kt                        ApiException 계열
    alert/DiscordWebhook.kt, DiscordErrorAppender.kt, AlertMarkers.kt
    notice/Notice.kt, StoredNotice.kt, StoredNoticeRepository.kt
    job/JobName.kt, JobState.kt, JobStateRepository.kt, JobRun.kt, JobRunRepository.kt
    user/AppUser.kt, AppUserRepository.kt, InviteCode.kt, InviteCodeRepository.kt, EmailToken.kt, EmailTokenRepository.kt
    watch/WatchItem.kt, WatchItemRepository.kt, UserFilter.kt, UserFilterRepository.kt
    delivery/Delivery.kt, DeliveryItem.kt, DeliveryRepositories.kt
    mail/OutboxMail.kt, OutboxMailRepository.kt
    cadence/Cadence.kt
  src/main/kotlin/db/migration/V5__migrate_legacy_filters.kt
  src/main/resources/db/migration/V1..V4, logback-spring.xml
  src/testFixtures/kotlin/com/narareminder/PostgresTestConfig.kt, notice/NoticeFixtures.kt
api/    build.gradle.kts
  src/main/kotlin/com/narareminder/
    ApiApplication.kt, config/AppProperties.kt, web/ApiExceptionHandler.kt
    security/AppPrincipal.kt, SecurityConfig.kt, CsrfCookieFilter.kt
    auth/Credentials.kt, AuthExceptions.kt, AuthDtos.kt, AccountService.kt, AuthService.kt, AdminBootstrap.kt,
         TokenCodec.kt, AuthMails.kt, SignupService.kt, PasswordResetService.kt, AuthController.kt
    items/ItemDtos.kt, ItemValues.kt, ItemQueries.kt, ItemService.kt, ItemController.kt
    settings/SettingsDtos.kt, SettingsService.kt, SettingsController.kt
    cron/CronController.kt
    admin/JobRunController.kt, InviteService.kt, InviteController.kt
  src/main/resources/application.yml
worker/ build.gradle.kts
  src/main/kotlin/com/narareminder/
    WorkerApplication.kt, config/NaraProperties.kt, config/SchedulingConfig.kt, support/Retry.kt
    client/NaraApiClient.kt, NoticeMapper.kt, NaraApiException.kt
    alert/DiscordNotifier.kt, mail/MailNotifier.kt, mail/MailQuota.kt
    job/NoticeJobs.kt, NoticePipeline.kt, QueryWindow.kt, NoticeCollector.kt, RetentionCleanup.kt
    matching/UserProfile.kt
    delivery/DeliveryDispatcher.kt, mail/OutboxSender.kt
  src/main/resources/application.yml
admin/src/  (React)  api/{client,auth,items,settings,cron,admin,runs}.ts, auth/AuthContext.tsx,
            hooks/useCronPreview.ts, cadence.ts, itemGroups.ts, format.ts, types.ts,
            components/{Header,CadenceEditor}.tsx,
            pages/{Login,Signup,Verify,Forgot,Reset,Items,NewItem,ItemDetail,Settings,Invites,Runs}Page.tsx
Dockerfile (빌드 인자 MODULE), docker-compose.yml, web/Caddyfile, .github/workflows/deploy.yml, deploy/remote-deploy.sh
```

---

### Task 1: Gradle 멀티 모듈로 옮기기 (동작 변화 없음)

기존 단일 모듈 코드를 `core`/`api`/`worker` 로 옮긴다. 이 Task 가 끝나면 기존 기능(필터 API, 수집·발송)이 모듈만 바뀐 채 그대로 동작하고 기존 테스트가 모두 통과해야 한다.

**Files:**
- Create: `settings.gradle.kts`(수정), `build.gradle.kts`(교체), `core/build.gradle.kts`, `api/build.gradle.kts`, `worker/build.gradle.kts`
- Create: `core/src/main/kotlin/com/narareminder/support/ClockConfig.kt`, `core/src/main/kotlin/com/narareminder/web/ApiException.kt`
- Create: `api/src/main/kotlin/com/narareminder/ApiApplication.kt`, `worker/src/main/kotlin/com/narareminder/WorkerApplication.kt`
- Create: `api/src/main/resources/application.yml`, `worker/src/main/resources/application.yml`, `worker/src/test/resources/application-test.yml`
- Create: `core/src/test/kotlin/com/narareminder/CoreTestApplication.kt`, `api/src/test/kotlin/com/narareminder/ApiApplicationTest.kt`, `worker/src/test/kotlin/com/narareminder/WorkerApplicationTest.kt`
- Move: `src/**` → 각 모듈 (아래 Step 1)
- Modify: `Dockerfile`, `.dockerignore`
- Delete: `src/main/kotlin/com/narareminder/NaraReminderApplication.kt`, `src/test/kotlin/com/narareminder/NaraReminderApplicationTest.kt`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: 모듈 `:core`(java-library + testFixtures), `:api`, `:worker`(Spring Boot 앱). 앱 클래스는 둘 다 패키지 `com.narareminder` 에 있어 core 의 엔티티·리포지토리·컴포넌트를 스캔한다. `com.narareminder.web.ApiException`, `NotFoundException` 은 core 에 있다. Clock 빈은 core `ClockConfig` 가 만든다.

- [ ] **Step 1: 파일 옮기기**

```bash
set -e
C=core/src/main/kotlin/com/narareminder; A=api/src/main/kotlin/com/narareminder; W=worker/src/main/kotlin/com/narareminder
CT=core/src/test/kotlin/com/narareminder; CF=core/src/testFixtures/kotlin/com/narareminder
AT=api/src/test/kotlin/com/narareminder; WT=worker/src/test/kotlin/com/narareminder
S=src/main/kotlin/com/narareminder; T=src/test/kotlin/com/narareminder
mkdir -p $C/{notice,alert,support,job,store,filter,web} $A/{filter,job,web} $W/{client,config,job,mail,alert,support} \
  $CT/{notice,alert,store,filter} $CF/notice $AT/{filter,job} $WT/{client,config,mail,alert,support,job} \
  core/src/main/resources worker/src/test/resources

git mv $S/notice/Notice.kt $S/notice/FilterCriteria.kt $S/notice/NoticeFilter.kt $C/notice/
git mv $S/alert/AlertMarkers.kt $S/alert/DiscordErrorAppender.kt $S/alert/DiscordWebhook.kt $C/alert/
git mv $S/support/Time.kt $C/support/
git mv $S/job/JobName.kt $S/job/JobState.kt $S/job/JobStateRepository.kt $S/job/JobRun.kt $S/job/JobRunRepository.kt $C/job/
git mv $S/store/NotifiedNotice.kt $S/store/NotifiedNoticeRepository.kt $C/store/
git mv $S/filter/FilterTerm.kt $S/filter/FilterSetting.kt $S/filter/FilterRepositories.kt $S/filter/FilterService.kt $S/filter/FilterExceptions.kt $C/filter/
git mv src/main/resources/db core/src/main/resources/db
git mv src/main/resources/logback-spring.xml core/src/main/resources/

git mv $S/filter/FilterController.kt $S/filter/FilterDtos.kt $A/filter/
git mv $S/job/JobRunController.kt $A/job/
git mv $S/web/ApiExceptionHandler.kt $A/web/

git mv $S/client/NaraApiClient.kt $S/client/NoticeMapper.kt $S/client/NaraApiException.kt $W/client/
git mv $S/config/NaraProperties.kt $S/config/SchedulingConfig.kt $W/config/
git mv $S/job/NoticePipeline.kt $S/job/NoticeJobs.kt $S/job/QueryWindow.kt $S/job/JobRunCleanup.kt $W/job/
git mv $S/mail/MailNotifier.kt $W/mail/
git mv $S/alert/DiscordNotifier.kt $W/alert/
git mv $S/support/Retry.kt $W/support/

git mv $T/PostgresTestConfig.kt $CF/
git mv $T/notice/NoticeFixtures.kt $CF/notice/
git mv $T/notice/NoticeTest.kt $T/notice/NoticeFilterTest.kt $CT/notice/
git mv $T/alert/DiscordErrorAppenderTest.kt $T/alert/DiscordWebhookTest.kt $CT/alert/
git mv $T/store/PersistenceTest.kt $CT/store/
git mv $T/filter/FilterServiceTest.kt $CT/filter/
git mv $T/filter/FilterApiTest.kt $AT/filter/
git mv $T/job/JobRunApiTest.kt $AT/job/
git mv $T/client/NaraApiClientTest.kt $WT/client/
git mv $T/config/NaraPropertiesTest.kt $T/config/TestProperties.kt $WT/config/
git mv $T/mail/MailNotifierTest.kt $WT/mail/
git mv $T/alert/DiscordNotifierTest.kt $WT/alert/
git mv $T/support/RetryTest.kt $WT/support/
git mv $T/job/NoticeJobsTest.kt $T/job/NoticePipelineTest.kt $T/job/QueryWindowTest.kt $T/job/JobRunCleanupTest.kt $WT/job/
git mv src/test/resources/fixtures worker/src/test/resources/fixtures
git mv src/main/resources/application.yml worker/src/main/resources/application.yml
git mv src/test/resources/application-test.yml worker/src/test/resources/application-test.yml
git rm -q $S/NaraReminderApplication.kt $T/NaraReminderApplicationTest.kt
find src -type f    # 아무것도 남지 않아야 한다
rm -rf src
```

- [ ] **Step 2: Gradle 설정 작성**

`settings.gradle.kts`:

```kotlin
rootProject.name = "nara-reminder"

include("core", "api", "worker")
```

`build.gradle.kts` (루트, 전체 교체):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    kotlin("plugin.jpa") version "2.2.20" apply false
}

subprojects {
    group = "com.narareminder"
    version = "0.0.1"

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

`core/build.gradle.kts`:

```kotlin
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    `java-test-fixtures`
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core")
    }
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

`api/build.gradle.kts`:

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":core")))
    testImplementation("io.mockk:mockk:1.14.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// bootJar 하나만 남겨 Dockerfile 이 jar 를 하나로 집을 수 있게 한다
tasks.named<Jar>("jar") {
    enabled = false
}
```

`worker/build.gradle.kts`:

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation(testFixtures(project(":core")))
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// bootJar 하나만 남겨 Dockerfile 이 jar 를 하나로 집을 수 있게 한다
tasks.named<Jar>("jar") {
    enabled = false
}
```

- [ ] **Step 3: 공통 코드 분리**

`api/src/main/kotlin/com/narareminder/web/ApiExceptionHandler.kt` 에서 `ApiException`, `NotFoundException` 정의를 지우고 아래 새 파일로 옮긴다(핸들러와 `ErrorResponse` 는 api 에 남는다). `ApiExceptionHandler.kt` 는 같은 패키지라 import 변경이 필요 없다.

`core/src/main/kotlin/com/narareminder/web/ApiException.kt`:

```kotlin
package com.narareminder.web

import org.springframework.http.HttpStatus

open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)
```

`core/build.gradle.kts` 의 의존성에 `HttpStatus` 를 위한 `api("org.springframework:spring-web")` 를 추가한다(`api("org.springframework.boot:spring-boot-starter-data-jpa")` 아래).

`core/src/main/kotlin/com/narareminder/support/ClockConfig.kt`:

```kotlin
package com.narareminder.support

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.system(KST)
}
```

`api/src/main/kotlin/com/narareminder/ApiApplication.kt`:

```kotlin
package com.narareminder

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
```

`worker/src/main/kotlin/com/narareminder/WorkerApplication.kt`:

```kotlin
package com.narareminder

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}
```

`core/src/test/kotlin/com/narareminder/CoreTestApplication.kt`:

```kotlin
package com.narareminder

import org.springframework.boot.autoconfigure.SpringBootApplication

// core 의 슬라이스 테스트(@DataJpaTest 등)가 찾을 설정 클래스
@SpringBootApplication
class CoreTestApplication
```

- [ ] **Step 4: 설정 파일 작성**

`worker/src/main/resources/application.yml` 은 옮겨 온 파일에서 `server:` 블록을 지우고 맨 위 `spring:` 아래를 다음처럼 바꾼다(나머지 `spring.datasource`, `jpa`, `mail`, `nara:` 는 그대로).

```yaml
spring:
  application:
    name: nara-reminder-worker
  main:
    # WebFlux 는 나라장터 API 호출(WebClient)에만 쓴다. 웹 서버는 띄우지 않는다
    web-application-type: none
  flyway:
    # 스키마 마이그레이션은 api 가 맡는다
    enabled: false
```

`worker/src/test/resources/application-test.yml` 은 옮겨 온 파일 맨 위 `spring:` 아래에 다음을 추가한다.

```yaml
  flyway:
    enabled: true
```

`api/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: nara-reminder-api
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/nara}
    username: ${DB_USERNAME:nara}
    password: ${DB_PASSWORD:nara}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate

server:
  port: 8080
  error:
    include-stacktrace: never

nara:
  # logback-spring.xml 의 ERROR 로그 디스코드 전송에 쓴다
  discord:
    webhook-url: ${DISCORD_WEBHOOK_URL:}
```

- [ ] **Step 5: 컨텍스트 기동 테스트 작성**

`worker/src/test/kotlin/com/narareminder/WorkerApplicationTest.kt`:

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
class WorkerApplicationTest @Autowired constructor(
    private val noticeJobs: NoticeJobs,
) {
    @Test
    fun `worker 컨텍스트가 설정·DB 마이그레이션과 함께 뜬다`() {
        assertThat(noticeJobs).isNotNull()
    }
}
```

`api/src/test/kotlin/com/narareminder/ApiApplicationTest.kt`:

```kotlin
package com.narareminder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class ApiApplicationTest @Autowired constructor(
    private val context: ApplicationContext,
) {
    @Test
    fun `api 컨텍스트에는 수집 스케줄러가 없다`() {
        assertThat(context.containsBean("noticeJobs")).isFalse()
    }
}
```

- [ ] **Step 6: Dockerfile 과 .dockerignore**

`Dockerfile` (전체 교체):

```dockerfile
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY core/build.gradle.kts core/
COPY api/build.gradle.kts api/
COPY worker/build.gradle.kts worker/
RUN ./gradlew --no-daemon :${MODULE}:dependencies > /dev/null
COPY core/src core/src
COPY ${MODULE}/src ${MODULE}/src
RUN ./gradlew --no-daemon :${MODULE}:bootJar

FROM eclipse-temurin:21-jre
ARG MODULE
ENV TZ=Asia/Seoul
WORKDIR /app
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
```

`.dockerignore` 의 `build` 줄을 `**/build` 로 바꾼다(모듈별 build 디렉터리 제외).

- [ ] **Step 7: 전체 테스트 실행**

Run: `./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL. 기존 테스트가 모두 통과하고 `WorkerApplicationTest`, `ApiApplicationTest` 가 추가로 통과한다. 컴파일 에러가 나면 옮긴 파일의 패키지 선언은 그대로이므로 import 대상이 다른 모듈(예: worker 클래스를 api 가 참조)에 있는지 확인한다.

Run: `docker build --build-arg MODULE=api -t nara-api-test . && docker build --build-arg MODULE=worker -t nara-worker-test .`
Expected: 두 이미지 모두 빌드 성공.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -F - <<'EOF'
refactor(build): core·api·worker 멀티 모듈로 분리

수집·발송 서버와 화면 API 서버를 나누기 위해 코드 위치만 옮기고 동작은 바꾸지 않음

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: 다중 사용자 스키마와 엔티티 (core)

**Files:**
- Create: `core/src/main/resources/db/migration/V4__multi_user.sql`
- Create: `core/src/main/kotlin/com/narareminder/user/{AppUser,AppUserRepository,InviteCode,InviteCodeRepository,EmailToken,EmailTokenRepository}.kt`
- Create: `core/src/main/kotlin/com/narareminder/watch/{WatchItem,WatchItemRepository,UserFilter,UserFilterRepository}.kt`
- Create: `core/src/main/kotlin/com/narareminder/notice/{StoredNotice,StoredNoticeRepository}.kt`
- Create: `core/src/main/kotlin/com/narareminder/delivery/{Delivery,DeliveryItem,DeliveryRepositories}.kt`
- Create: `core/src/main/kotlin/com/narareminder/mail/{OutboxMail,OutboxMailRepository}.kt`
- Modify: `core/src/main/kotlin/com/narareminder/job/JobRunRepository.kt`
- Test: `core/src/test/kotlin/com/narareminder/MultiUserSchemaTest.kt`

**Interfaces:**
- Produces (모두 `com.narareminder.*`):
  - `user.AppUser(email: String, passwordHash: String?, isAdmin: Boolean, createdAt: Instant, status: UserStatus = ACTIVE, failedCount: Int = 0, lockedUntil: Instant? = null, id: Long? = null)`; `var passwordHash`, `var isAdmin`, `var status`; `val isClaimed: Boolean`; `isLocked(now): Boolean`; `recordLoginFailure(now, maxFailures: Int, lockFor: Duration)`; `recordLoginSuccess()`; `changePassword(hash: String)`. `enum UserStatus { ACTIVE, DISABLED }`
  - `user.AppUserRepository`: `findByEmail(email: String): AppUser?`, `findAllByStatus(status: UserStatus): List<AppUser>`, `findAdmins(): List<AppUser>`
  - `user.InviteCode(code, maxUses: Int, expiresAt, createdBy: Long, createdAt, usedCount: Int = 0, revokedAt: Instant? = null, id)`; `status(now): InviteStatus`; `isUsable(now)`; `use()`; `revoke(now)`. `enum InviteStatus { ACTIVE, EXHAUSTED, EXPIRED, REVOKED }`
  - `user.InviteCodeRepository`: `findByCode(code): InviteCode?`, `findForUpdate(id: Long): InviteCode?`, `findAllByOrderByIdDesc(): List<InviteCode>`
  - `user.EmailToken(email, purpose: TokenPurpose, tokenHash, passwordHash: String?, inviteCodeId: Long?, expiresAt, createdAt, usedAt: Instant? = null, id)`; `var usedAt`; `isUsable(purpose, now)`. `enum TokenPurpose { VERIFY, RESET }`
  - `user.EmailTokenRepository`: `findByTokenHash(hash): EmailToken?`, `deleteUsedOrExpired(now: Instant): Int`
  - `watch.WatchItem(userId: Long, type: WatchItemType, value: String, cron: String, createdAt: Instant, id: Long? = null)`; `var value`, `var cron`. `enum WatchItemType(val label: String) { KEYWORD("키워드"), AGENCY("수요기관") }`
  - `watch.WatchItemRepository`: `findAllByUserIdOrderByIdAsc(userId)`, `findAllByUserIdIn(userIds: Collection<Long>)`, `findByIdAndUserId(id, userId): WatchItem?`, `existsByUserIdAndTypeAndValueIgnoreCase(userId, type, value)`, `existsByUserIdAndTypeAndValueIgnoreCaseAndIdNot(userId, type, value, id)`
  - `watch.UserFilter(userId: Long, excludeKeywords: String = "", categories: String = ALL_CATEGORIES, priceMin: Long? = null, priceMax: Long? = null)`; `excludeKeywordList(): List<String>`; `categoryList(): List<NoticeCategory>`; `update(excludeKeywords: List<String>, categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?)`
  - `watch.UserFilterRepository : JpaRepository<UserFilter, Long>`
  - `notice.NoticeKey(source: NoticeSource, noticeNo: String, noticeOrd: String)`; `Notice.key(): NoticeKey`
  - `notice.StoredNotice(id: NoticeKey, title, category: NoticeCategory?, price: Long?, agency: String?, postedAt: LocalDateTime?, deadlineAt: LocalDateTime?, detailUrl: String?, collectedAt: Instant)`; `val isRevision`; `StoredNotice.of(notice: Notice, collectedAt: Instant)`
  - `notice.StoredNoticeRepository : JpaRepository<StoredNotice, NoticeKey>`; `deleteCollectedBefore(cutoff: Instant): Int`
  - `delivery.Delivery(userId: Long, notice: NoticeKey, matchedAt: Instant, status = PENDING, attempts = 0, sentAt: Instant? = null, id)`; `requireId(): Long`; `markSent(at)`; `recordFailure(maxAttempts: Int): Boolean`. `enum DeliveryStatus { PENDING, SENT, FAILED }`
  - `delivery.DeliveryItem(id: DeliveryItemId)`, `DeliveryItemId(deliveryId: Long, watchItemId: Long)`
  - `delivery.DeliveryRepository`: `findAllByStatus(status)`; `delivery.DeliveryItemRepository`: `findAllByIdDeliveryIdIn(ids: Collection<Long>)`
  - `mail.OutboxMail(toAddress, subject, html, createdAt, status = PENDING, attempts = 0, sentAt = null, id)`; `markSent(at)`; `recordFailure(maxAttempts): Boolean`. `enum OutboxStatus { PENDING, SENT, FAILED }`
  - `mail.OutboxMailRepository`: `findTop20ByStatusOrderByIdAsc(status)`, `countByStatusAndSentAtGreaterThanEqual(status, since): Long`, `deleteByStatusNotAndCreatedAtBefore(status, cutoff): Long`
  - `job.JobRunRepository` 에 `sumSentCountSince(job: JobName, since: Instant): Long` 추가

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/com/narareminder/MultiUserSchemaTest.kt`:

```kotlin
package com.narareminder

import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.mail.OutboxMail
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.mail.OutboxStatus
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.notice.key
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class MultiUserSchemaTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
    private val outbox: OutboxMailRepository,
    private val em: TestEntityManager,
) {
    private val now = Instant.parse("2026-10-31T05:00:00Z")

    private fun aUser(email: String = "a@example.com"): Long =
        requireNotNull(users.save(AppUser(email, "hash", false, now)).id)

    private fun pendingDelivery(userId: Long, itemId: Long): Long {
        val notice = notices.save(StoredNotice.of(aNotice(noticeNo = "S1"), now))
        val delivery = deliveries.save(Delivery(userId, notice.id, now))
        deliveryItems.save(DeliveryItem(DeliveryItemId(delivery.requireId(), itemId)))
        em.flush()
        return delivery.requireId()
    }

    @Test
    fun `사용자·항목·공통 조건을 저장하고 조회한다`() {
        val userId = aUser()
        filters.save(UserFilter(userId).apply { update(listOf("교육", "청소"), listOf(NoticeCategory.SERVICE), 1_000, null) })
        items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now))
        em.flush()
        em.clear()

        assertThat(users.findByEmail("a@example.com")?.isClaimed).isTrue()
        val filter = filters.findById(userId).orElseThrow()
        assertThat(filter.excludeKeywordList()).containsExactly("교육", "청소")
        assertThat(filter.categoryList()).containsExactly(NoticeCategory.SERVICE)
        assertThat(items.findAllByUserIdOrderByIdAsc(userId).map { it.value }).containsExactly("플랫폼")
    }

    @Test
    fun `같은 사용자의 같은 종류 항목은 대소문자만 달라도 중복 저장할 수 없다`() {
        val userId = aUser()
        items.saveAndFlush(WatchItem(userId, WatchItemType.KEYWORD, "ODA", "0 */5 * * * *", now))

        assertThatThrownBy { items.saveAndFlush(WatchItem(userId, WatchItemType.KEYWORD, "oda", "0 */5 * * * *", now)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `항목을 지우면 발송 대기에 걸린 항목도 지워진다`() {
        val userId = aUser()
        val itemId = requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now)).id)
        val deliveryId = pendingDelivery(userId, itemId)
        em.clear()

        items.deleteById(itemId)
        em.flush()
        em.clear()

        assertThat(deliveryItems.findAllByIdDeliveryIdIn(listOf(deliveryId))).isEmpty()
        assertThat(deliveries.findById(deliveryId).orElseThrow().status).isEqualTo(DeliveryStatus.PENDING)
    }

    @Test
    fun `오래된 공고를 지우면 그 공고의 발송 대기도 지워진다`() {
        val userId = aUser()
        val itemId = requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, "플랫폼", "0 */5 * * * *", now)).id)
        val deliveryId = pendingDelivery(userId, itemId)
        em.clear()

        val deleted = notices.deleteCollectedBefore(now.plusSeconds(1))
        em.clear()

        assertThat(deleted).isEqualTo(1)
        assertThat(deliveries.findById(deliveryId)).isEmpty()
        assertThat(notices.findById(aNotice(noticeNo = "S1").key())).isEmpty()
    }

    @Test
    fun `outbox 메일을 오래된 순으로 꺼낸다`() {
        outbox.save(OutboxMail("a@example.com", "첫째", "<p>1</p>", now))
        outbox.save(OutboxMail("b@example.com", "둘째", "<p>2</p>", now))
        outbox.save(OutboxMail("c@example.com", "보냄", "<p>3</p>", now).apply { markSent(now) })

        assertThat(outbox.findTop20ByStatusOrderByIdAsc(OutboxStatus.PENDING).map { it.subject }).containsExactly("첫째", "둘째")
        assertThat(outbox.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, now)).isEqualTo(1)
    }

    @Test
    fun `실패가 한도에 닿으면 FAILED 로 바뀐다`() {
        val delivery = Delivery(1, aNotice().key(), now)

        assertThat(delivery.recordFailure(3)).isFalse()
        assertThat(delivery.recordFailure(3)).isFalse()
        assertThat(delivery.recordFailure(3)).isTrue()
        assertThat(delivery.status).isEqualTo(DeliveryStatus.FAILED)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests 'com.narareminder.MultiUserSchemaTest' --no-daemon`
Expected: 컴파일 실패 (`AppUser`, `WatchItem` 등 없음)

- [ ] **Step 3: 마이그레이션 작성**

`core/src/main/resources/db/migration/V4__multi_user.sql`:

```sql
CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    email          VARCHAR(254) NOT NULL UNIQUE,  -- 소문자로 저장
    password_hash  VARCHAR(100),                  -- NULL 이면 아직 주인이 가입하지 않은 계정
    is_admin       BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(10)  NOT NULL,         -- ACTIVE | DISABLED
    failed_count   INT          NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE invite_code (
    id          BIGSERIAL   PRIMARY KEY,
    code        VARCHAR(16) NOT NULL UNIQUE,
    max_uses    INT         NOT NULL,
    used_count  INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_by  BIGINT      NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE email_token (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    purpose         VARCHAR(10)  NOT NULL,   -- VERIFY | RESET
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(100),            -- VERIFY 만
    invite_code_id  BIGINT       REFERENCES invite_code (id),
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE watch_item (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type        VARCHAR(10)  NOT NULL,   -- KEYWORD | AGENCY
    value       VARCHAR(100) NOT NULL,
    cron        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uq_watch_item_value ON watch_item (user_id, type, lower(value));

CREATE TABLE user_filter (
    user_id           BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    exclude_keywords  TEXT         NOT NULL DEFAULT '',  -- 줄바꿈 구분
    categories        VARCHAR(100) NOT NULL,             -- 쉼표 구분
    price_min         BIGINT,
    price_max         BIGINT
);

CREATE TABLE notice (
    source        VARCHAR(16) NOT NULL,
    notice_no     VARCHAR(64) NOT NULL,
    notice_ord    VARCHAR(16) NOT NULL,
    title         TEXT        NOT NULL,
    category      VARCHAR(16),            -- 이관한 행은 NULL
    price         BIGINT,
    agency        TEXT,
    posted_at     TIMESTAMP,
    deadline_at   TIMESTAMP,
    detail_url    TEXT,
    collected_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, notice_no, notice_ord)
);
CREATE INDEX idx_notice_collected ON notice (collected_at);

CREATE TABLE delivery (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    source      VARCHAR(16) NOT NULL,
    notice_no   VARCHAR(64) NOT NULL,
    notice_ord  VARCHAR(16) NOT NULL,
    status      VARCHAR(10) NOT NULL,   -- PENDING | SENT | FAILED
    attempts    INT         NOT NULL DEFAULT 0,
    matched_at  TIMESTAMPTZ NOT NULL,
    sent_at     TIMESTAMPTZ,
    UNIQUE (user_id, source, notice_no, notice_ord),
    FOREIGN KEY (source, notice_no, notice_ord) REFERENCES notice (source, notice_no, notice_ord) ON DELETE CASCADE
);
CREATE INDEX idx_delivery_pending ON delivery (status) WHERE status = 'PENDING';

CREATE TABLE delivery_item (
    delivery_id    BIGINT NOT NULL REFERENCES delivery (id) ON DELETE CASCADE,
    watch_item_id  BIGINT NOT NULL REFERENCES watch_item (id) ON DELETE CASCADE,
    PRIMARY KEY (delivery_id, watch_item_id)
);
CREATE INDEX idx_delivery_item_watch ON delivery_item (watch_item_id);

CREATE TABLE outbox_mail (
    id          BIGSERIAL    PRIMARY KEY,
    to_address  VARCHAR(254) NOT NULL,
    subject     VARCHAR(200) NOT NULL,
    html        TEXT         NOT NULL,
    status      VARCHAR(10)  NOT NULL,   -- PENDING | SENT | FAILED
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    sent_at     TIMESTAMPTZ
);
```

- [ ] **Step 4: 엔티티·리포지토리 작성**

`core/src/main/kotlin/com/narareminder/user/AppUser.kt`:

```kotlin
package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

enum class UserStatus { ACTIVE, DISABLED }

@Entity
@Table(name = "app_user")
class AppUser(
    val email: String,
    var passwordHash: String?,
    var isAdmin: Boolean,
    val createdAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: UserStatus = UserStatus.ACTIVE,
    var failedCount: Int = 0,
    var lockedUntil: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    // 비밀번호가 없으면 미리 만들어 둔 계정으로, 같은 이메일로 가입해 이어받을 수 있다
    val isClaimed: Boolean
        get() = passwordHash != null

    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    fun recordLoginFailure(now: Instant, maxFailures: Int, lockFor: Duration) {
        failedCount++
        if (failedCount >= maxFailures) {
            lockedUntil = now.plus(lockFor)
            failedCount = 0
        }
    }

    fun recordLoginSuccess() {
        failedCount = 0
        lockedUntil = null
    }

    fun changePassword(hash: String) {
        passwordHash = hash
        recordLoginSuccess()
    }
}
```

`core/src/main/kotlin/com/narareminder/user/AppUserRepository.kt`:

```kotlin
package com.narareminder.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByEmail(email: String): AppUser?
    fun findAllByStatus(status: UserStatus): List<AppUser>

    @Query("select u from AppUser u where u.isAdmin = true")
    fun findAdmins(): List<AppUser>
}
```

`core/src/main/kotlin/com/narareminder/user/InviteCode.kt`:

```kotlin
package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class InviteStatus { ACTIVE, EXHAUSTED, EXPIRED, REVOKED }

@Entity
@Table(name = "invite_code")
class InviteCode(
    val code: String,
    val maxUses: Int,
    val expiresAt: Instant,
    val createdBy: Long,
    val createdAt: Instant,
    var usedCount: Int = 0,
    var revokedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun status(now: Instant): InviteStatus = when {
        revokedAt != null -> InviteStatus.REVOKED
        usedCount >= maxUses -> InviteStatus.EXHAUSTED
        !expiresAt.isAfter(now) -> InviteStatus.EXPIRED
        else -> InviteStatus.ACTIVE
    }

    fun isUsable(now: Instant): Boolean = status(now) == InviteStatus.ACTIVE

    fun use() {
        usedCount++
    }

    fun revoke(now: Instant) {
        revokedAt = now
    }
}
```

`core/src/main/kotlin/com/narareminder/user/InviteCodeRepository.kt`:

```kotlin
package com.narareminder.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface InviteCodeRepository : JpaRepository<InviteCode, Long> {
    fun findByCode(code: String): InviteCode?
    fun findAllByOrderByIdDesc(): List<InviteCode>

    // 동시에 확인 링크를 눌러도 사용 한도를 넘지 않도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InviteCode i where i.id = :id")
    fun findForUpdate(id: Long): InviteCode?
}
```

`core/src/main/kotlin/com/narareminder/user/EmailToken.kt`:

```kotlin
package com.narareminder.user

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class TokenPurpose { VERIFY, RESET }

@Entity
@Table(name = "email_token")
class EmailToken(
    val email: String,
    @Enumerated(EnumType.STRING)
    val purpose: TokenPurpose,
    val tokenHash: String,
    val passwordHash: String?,
    val inviteCodeId: Long?,
    val expiresAt: Instant,
    val createdAt: Instant,
    var usedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun isUsable(purpose: TokenPurpose, now: Instant): Boolean =
        this.purpose == purpose && usedAt == null && expiresAt.isAfter(now)
}
```

`core/src/main/kotlin/com/narareminder/user/EmailTokenRepository.kt`:

```kotlin
package com.narareminder.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface EmailTokenRepository : JpaRepository<EmailToken, Long> {
    fun findByTokenHash(tokenHash: String): EmailToken?

    @Modifying
    @Query("delete from EmailToken t where t.usedAt is not null or t.expiresAt < :now")
    fun deleteUsedOrExpired(now: Instant): Int
}
```

`core/src/main/kotlin/com/narareminder/watch/WatchItem.kt`:

```kotlin
package com.narareminder.watch

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class WatchItemType(val label: String) {
    KEYWORD("키워드"),
    AGENCY("수요기관"),
}

@Entity
@Table(name = "watch_item")
class WatchItem(
    val userId: Long,
    @Enumerated(EnumType.STRING)
    val type: WatchItemType,
    var value: String,
    var cron: String,
    val createdAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
```

`core/src/main/kotlin/com/narareminder/watch/WatchItemRepository.kt`:

```kotlin
package com.narareminder.watch

import org.springframework.data.jpa.repository.JpaRepository

interface WatchItemRepository : JpaRepository<WatchItem, Long> {
    fun findAllByUserIdOrderByIdAsc(userId: Long): List<WatchItem>
    fun findAllByUserIdIn(userIds: Collection<Long>): List<WatchItem>
    fun findByIdAndUserId(id: Long, userId: Long): WatchItem?
    fun existsByUserIdAndTypeAndValueIgnoreCase(userId: Long, type: WatchItemType, value: String): Boolean
    fun existsByUserIdAndTypeAndValueIgnoreCaseAndIdNot(userId: Long, type: WatchItemType, value: String, id: Long): Boolean
}
```

`core/src/main/kotlin/com/narareminder/watch/UserFilter.kt`:

```kotlin
package com.narareminder.watch

import com.narareminder.notice.NoticeCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_filter")
class UserFilter(
    @Id
    val userId: Long,
    @Column(columnDefinition = "text")
    var excludeKeywords: String = "",
    var categories: String = ALL_CATEGORIES,
    var priceMin: Long? = null,
    var priceMax: Long? = null,
) {
    fun excludeKeywordList(): List<String> = excludeKeywords.split('\n').filter { it.isNotBlank() }

    fun categoryList(): List<NoticeCategory> =
        categories.split(",").filter { it.isNotBlank() }.map { NoticeCategory.valueOf(it.trim()) }

    fun update(excludeKeywords: List<String>, categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?) {
        this.excludeKeywords = excludeKeywords.joinToString("\n")
        this.categories = categories.joinToString(",") { it.name }
        this.priceMin = priceMin
        this.priceMax = priceMax
    }

    companion object {
        val ALL_CATEGORIES: String = NoticeCategory.entries.joinToString(",") { it.name }
    }
}
```

`core/src/main/kotlin/com/narareminder/watch/UserFilterRepository.kt`:

```kotlin
package com.narareminder.watch

import org.springframework.data.jpa.repository.JpaRepository

interface UserFilterRepository : JpaRepository<UserFilter, Long>
```

`core/src/main/kotlin/com/narareminder/notice/StoredNotice.kt`:

```kotlin
package com.narareminder.notice

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime

@Embeddable
data class NoticeKey(
    @Enumerated(EnumType.STRING)
    val source: NoticeSource,
    val noticeNo: String,
    val noticeOrd: String,
) : Serializable

fun Notice.key() = NoticeKey(source, noticeNo, noticeOrd)

@Entity
@Table(name = "notice")
class StoredNotice(
    @EmbeddedId
    val id: NoticeKey,
    @Column(columnDefinition = "text")
    val title: String,
    @Enumerated(EnumType.STRING)
    val category: NoticeCategory?,
    val price: Long?,
    @Column(columnDefinition = "text")
    val agency: String?,
    val postedAt: LocalDateTime?,
    val deadlineAt: LocalDateTime?,
    @Column(columnDefinition = "text")
    val detailUrl: String?,
    val collectedAt: Instant,
) {
    val isRevision: Boolean
        get() = id.source == NoticeSource.BID && (id.noticeOrd.toIntOrNull() ?: 0) > 0

    companion object {
        fun of(notice: Notice, collectedAt: Instant) = StoredNotice(
            id = notice.key(), title = notice.title, category = notice.category, price = notice.price,
            agency = notice.agency, postedAt = notice.postedAt, deadlineAt = notice.deadlineAt,
            detailUrl = notice.detailUrl, collectedAt = collectedAt,
        )
    }
}
```

`core/src/main/kotlin/com/narareminder/notice/StoredNoticeRepository.kt`:

```kotlin
package com.narareminder.notice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface StoredNoticeRepository : JpaRepository<StoredNotice, NoticeKey> {
    // 발송 대기(delivery)와 걸린 항목은 DB 의 ON DELETE CASCADE 로 함께 지워진다
    @Modifying
    @Query("delete from StoredNotice n where n.collectedAt < :cutoff")
    fun deleteCollectedBefore(cutoff: Instant): Int
}
```

`core/src/main/kotlin/com/narareminder/delivery/Delivery.kt`:

```kotlin
package com.narareminder.delivery

import com.narareminder.notice.NoticeKey
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class DeliveryStatus { PENDING, SENT, FAILED }

@Entity
@Table(name = "delivery")
class Delivery(
    val userId: Long,
    @Embedded
    val notice: NoticeKey,
    val matchedAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: DeliveryStatus = DeliveryStatus.PENDING,
    var attempts: Int = 0,
    var sentAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun requireId(): Long = requireNotNull(id) { "저장되지 않은 발송 대기입니다" }

    fun markSent(at: Instant) {
        status = DeliveryStatus.SENT
        sentAt = at
    }

    // 실패 횟수가 한도에 닿으면 FAILED 로 바꾸고 true 를 돌려준다
    fun recordFailure(maxAttempts: Int): Boolean {
        attempts++
        if (attempts < maxAttempts) return false
        status = DeliveryStatus.FAILED
        return true
    }
}
```

`core/src/main/kotlin/com/narareminder/delivery/DeliveryItem.kt`:

```kotlin
package com.narareminder.delivery

import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Embeddable
data class DeliveryItemId(
    val deliveryId: Long,
    val watchItemId: Long,
) : Serializable

// 발송 대기 공고가 어떤 항목에 걸렸는지. 발송 시점 계산과 상세 화면에 쓴다
@Entity
@Table(name = "delivery_item")
class DeliveryItem(
    @EmbeddedId
    val id: DeliveryItemId,
)
```

`core/src/main/kotlin/com/narareminder/delivery/DeliveryRepositories.kt`:

```kotlin
package com.narareminder.delivery

import org.springframework.data.jpa.repository.JpaRepository

interface DeliveryRepository : JpaRepository<Delivery, Long> {
    fun findAllByStatus(status: DeliveryStatus): List<Delivery>
}

interface DeliveryItemRepository : JpaRepository<DeliveryItem, DeliveryItemId> {
    fun findAllByIdDeliveryIdIn(deliveryIds: Collection<Long>): List<DeliveryItem>
}
```

`core/src/main/kotlin/com/narareminder/mail/OutboxMail.kt`:

```kotlin
package com.narareminder.mail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class OutboxStatus { PENDING, SENT, FAILED }

// api 가 넣고 worker 가 보내는 인증 메일 (api 에는 SMTP 비밀번호를 두지 않는다)
@Entity
@Table(name = "outbox_mail")
class OutboxMail(
    val toAddress: String,
    val subject: String,
    @Column(columnDefinition = "text")
    val html: String,
    val createdAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: OutboxStatus = OutboxStatus.PENDING,
    var attempts: Int = 0,
    var sentAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun markSent(at: Instant) {
        status = OutboxStatus.SENT
        sentAt = at
    }

    // 실패 횟수가 한도에 닿으면 FAILED 로 바꾸고 true 를 돌려준다
    fun recordFailure(maxAttempts: Int): Boolean {
        attempts++
        if (attempts < maxAttempts) return false
        status = OutboxStatus.FAILED
        return true
    }
}
```

`core/src/main/kotlin/com/narareminder/mail/OutboxMailRepository.kt`:

```kotlin
package com.narareminder.mail

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface OutboxMailRepository : JpaRepository<OutboxMail, Long> {
    fun findTop20ByStatusOrderByIdAsc(status: OutboxStatus): List<OutboxMail>
    fun countByStatusAndSentAtGreaterThanEqual(status: OutboxStatus, since: Instant): Long
    fun deleteByStatusNotAndCreatedAtBefore(status: OutboxStatus, cutoff: Instant): Long
}
```

`core/src/main/kotlin/com/narareminder/job/JobRunRepository.kt` 에 메서드를 추가한다(기존 `deleteByStartedAtBefore` 아래):

```kotlin
    @Query("select coalesce(sum(r.sentCount), 0) from JobRun r where r.jobName = :job and r.startedAt >= :since")
    fun sumSentCountSince(job: JobName, since: Instant): Long
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :core:test --no-daemon`
Expected: PASS (기존 core 테스트 포함)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(core): 다중 사용자 스키마와 엔티티 추가

사용자·초대 코드·항목별 주기·발송 대기함·인증 메일 outbox 를 저장하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 3: 기존 필터 이관 V5 (core)

**Files:**
- Create: `core/src/main/kotlin/db/migration/V5__migrate_legacy_filters.kt`
- Create: `core/src/test/resources/application-test.yml`, `api/src/test/resources/application-test.yml`
- Modify: `worker/src/test/resources/application-test.yml`, `api/src/main/resources/application.yml`
- Test: `core/src/test/kotlin/com/narareminder/LegacyFilterMigrationTest.kt`

**Interfaces:**
- Consumes: V4 테이블(Task 2)
- Produces: Flyway placeholder `legacyowner`(값 = `LEGACY_OWNER_EMAIL`). V5 는 `filter_term` 이 비어 있으면 아무것도 하지 않는다. 테스트 DB 는 V3 시드 때문에 항상 이관이 일어나므로 모든 모듈의 테스트 설정에 placeholder 를 넣는다(이관 계정 `legacy-owner@example.com` 이 테스트 DB 에 항상 있다. 다른 테스트는 사용자별로 결과를 검사해야 한다).

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/resources/application-test.yml`:

```yaml
spring:
  flyway:
    placeholders:
      legacyowner: legacy-owner@example.com
```

`core/src/test/kotlin/com/narareminder/LegacyFilterMigrationTest.kt`:

```kotlin
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
import javax.sql.DataSource

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests 'com.narareminder.LegacyFilterMigrationTest' --no-daemon`
Expected: FAIL (V5 가 없어 `watch_item` 이 비어 있음, 두 번째 테스트는 예외가 나지 않음)

- [ ] **Step 3: V5 마이그레이션 작성**

`core/src/main/kotlin/db/migration/V5__migrate_legacy_filters.kt`:

```kotlin
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
```

- [ ] **Step 4: 다른 모듈 설정에 placeholder 추가**

`api/src/main/resources/application.yml` 의 `spring:` 아래에 추가:

```yaml
  flyway:
    placeholders:
      # V5 이관 계정. 기존 필터가 있는 DB 에서 비어 있으면 api 가 뜨지 않는다
      legacyowner: ${LEGACY_OWNER_EMAIL:}
```

`api/src/test/resources/application-test.yml` (새 파일):

```yaml
spring:
  flyway:
    placeholders:
      legacyowner: legacy-owner@example.com
```

`worker/src/test/resources/application-test.yml` 의 `spring.flyway` 아래에 같은 placeholder 를 추가한다:

```yaml
  flyway:
    enabled: true
    placeholders:
      legacyowner: legacy-owner@example.com
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --no-daemon`
Expected: PASS (세 모듈 모두. api·worker 컨텍스트도 V5 를 거쳐 뜬다)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(core): 기존 필터를 이관 계정으로 옮기는 V5 마이그레이션 추가

운영 중인 키워드·수요기관을 다중 사용자 구조로 옮기고, 이미 보낸 공고가 전환 직후 다시 발송되지 않게 하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: 발송 주기 판정 `Cadence` (core)

**Files:**
- Create: `core/src/main/kotlin/com/narareminder/cadence/Cadence.kt`
- Test: `core/src/test/kotlin/com/narareminder/cadence/CadenceTest.kt`

**Interfaces:**
- Produces:
  - `class InvalidCadenceException(message: String) : ApiException(BAD_REQUEST, "INVALID_CRON", message)`
  - `object Cadence { const val IMMEDIATE = "0 */5 * * * *"; fun validate(expr: String, now: Instant): String; fun nextTimes(expr: String, after: Instant, count: Int): List<Instant>; fun nextFire(expr: String, after: Instant): Instant?; fun earliestFire(crons: Collection<String>, after: Instant): Instant?; fun isDue(crons: Collection<String>, matchedAt: Instant, now: Instant): Boolean }`
  - `validate` 는 앞뒤 공백을 지운 식을 돌려준다. 모든 계산은 Asia/Seoul.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/kotlin/com/narareminder/cadence/CadenceTest.kt`:

```kotlin
package com.narareminder.cadence

import com.narareminder.support.KST
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class CadenceTest {

    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    // 2026-10-30 은 금요일
    private val now = kst("2026-10-30T14:03:00")

    @Test
    fun `5분 주기는 통과하고 앞뒤 공백을 지운다`() {
        assertThat(Cadence.validate("  0 */5 * * * *  ", now)).isEqualTo("0 */5 * * * *")
    }

    @Test
    fun `5분보다 촘촘한 주기는 거절한다`() {
        assertThatThrownBy { Cadence.validate("0 * * * * *", now) }
            .isInstanceOf(InvalidCadenceException::class.java)
            .hasMessageContaining("5분")
    }

    @Test
    fun `하루 중 짧은 간격이 섞여 있어도 거절한다`() {
        assertThatThrownBy { Cadence.validate("0 0,1 9 * * *", now) }.isInstanceOf(InvalidCadenceException::class.java)
    }

    @Test
    fun `형식이 틀리면 거절한다`() {
        assertThatThrownBy { Cadence.validate("매일 아침", now) }
            .isInstanceOf(InvalidCadenceException::class.java)
            .hasMessageContaining("cron")
    }

    @Test
    fun `평일 업무시간 30분 주기는 금요일 저녁 다음에 월요일 9시에 울린다`() {
        assertThat(Cadence.nextTimes("0 */30 9-17 * * MON-FRI", kst("2026-10-30T17:45:00"), 2))
            .containsExactly(kst("2026-11-02T09:00:00"), kst("2026-11-02T09:30:00"))
    }

    @Test
    fun `매칭 뒤 첫 cron 시각부터 발송 대상이다`() {
        val matchedAt = kst("2026-10-30T14:03:10")

        assertThat(Cadence.isDue(listOf(Cadence.IMMEDIATE), matchedAt, kst("2026-10-30T14:04:59"))).isFalse()
        assertThat(Cadence.isDue(listOf(Cadence.IMMEDIATE), matchedAt, kst("2026-10-30T14:05:00"))).isTrue()
    }

    @Test
    fun `여러 항목에 걸리면 가장 빠른 주기를 따른다`() {
        val matchedAt = kst("2026-10-30T14:03:10")
        val crons = listOf("0 0 9 * * *", Cadence.IMMEDIATE)

        assertThat(Cadence.earliestFire(crons, matchedAt)).isEqualTo(kst("2026-10-30T14:05:00"))
        assertThat(Cadence.isDue(listOf("0 0 9 * * *"), matchedAt, kst("2026-10-30T14:05:00"))).isFalse()
    }

    @Test
    fun `판정을 건너뛰어도 나중 판정에서 발송 대상이다`() {
        val matchedAt = kst("2026-10-30T14:03:10")

        assertThat(Cadence.isDue(listOf("0 0 9 * * *"), matchedAt, kst("2026-10-31T15:00:00"))).isTrue()
    }

    @Test
    fun `걸린 항목이 없으면 발송 대상이 아니다`() {
        assertThat(Cadence.isDue(emptyList(), now, now.plusSeconds(3600))).isFalse()
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests 'com.narareminder.cadence.CadenceTest' --no-daemon`
Expected: 컴파일 실패 (`Cadence` 없음)

- [ ] **Step 3: 구현**

`core/src/main/kotlin/com/narareminder/cadence/Cadence.kt`:

```kotlin
package com.narareminder.cadence

import com.narareminder.support.KST
import com.narareminder.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant

class InvalidCadenceException(message: String) : ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON", message)

// 항목별 발송 주기(cron). api 의 검증·미리보기와 worker 의 발송 판정이 같은 계산을 쓴다
object Cadence {
    const val IMMEDIATE = "0 */5 * * * *"

    // 수집이 5분마다라 더 촘촘하게 보내도 새 공고가 없다
    private val MIN_INTERVAL: Duration = Duration.ofMinutes(5)
    private const val INTERVAL_CHECKS = 10

    fun validate(expr: String, now: Instant): String {
        val trimmed = expr.trim()
        val times = nextTimes(trimmed, now, INTERVAL_CHECKS + 1)
        if (times.isEmpty()) throw InvalidCadenceException("발송 시각이 없는 cron 입니다")
        if (times.zipWithNext().any { (a, b) -> Duration.between(a, b) < MIN_INTERVAL }) {
            throw InvalidCadenceException("발송 간격은 5분 이상이어야 합니다")
        }
        return trimmed
    }

    fun nextTimes(expr: String, after: Instant, count: Int): List<Instant> {
        val cron = parse(expr)
        return generateSequence(cron.next(after.atZone(KST))) { cron.next(it) }
            .take(count)
            .map { it.toInstant() }
            .toList()
    }

    fun nextFire(expr: String, after: Instant): Instant? = parse(expr).next(after.atZone(KST))?.toInstant()

    fun earliestFire(crons: Collection<String>, after: Instant): Instant? = crons.mapNotNull { nextFire(it, after) }.minOrNull()

    // 저장된 다음 발송 시각 없이 매칭 시각만으로 판정하므로, 판정을 건너뛰거나 주기를 바꿔도 누락되지 않는다
    fun isDue(crons: Collection<String>, matchedAt: Instant, now: Instant): Boolean =
        earliestFire(crons, matchedAt)?.let { !it.isAfter(now) } ?: false

    private fun parse(expr: String): CronExpression =
        try {
            CronExpression.parse(expr.trim())
        } catch (e: IllegalArgumentException) {
            throw InvalidCadenceException("cron 형식이 올바르지 않습니다: ${e.message}")
        }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests 'com.narareminder.cadence.CadenceTest' --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(core): 항목별 발송 주기 판정 추가

매칭 시각 이후 첫 cron 시각으로 발송을 판정해 판정 누락·주기 변경에도 빠짐없이 보내기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: 수집 → 저장 → 사용자별 매칭 (worker)

수집 결과를 메일로 바로 보내지 않고 `notice` 에 저장한 뒤 사용자별 발송 대기(`delivery`)를 만든다. 메일 발송은 Task 6 에서 다시 붙인다(이 Task 가 끝난 시점에는 알림 메일이 나가지 않는다. 운영 배포는 Task 16 뒤에만 한다).

**Files:**
- Modify: `core/src/main/kotlin/com/narareminder/job/JobName.kt`
- Create: `worker/src/main/kotlin/com/narareminder/matching/UserProfile.kt`
- Create: `worker/src/main/kotlin/com/narareminder/job/NoticeCollector.kt`
- Modify: `worker/src/main/kotlin/com/narareminder/job/NoticePipeline.kt` (전체 교체)
- Modify: `worker/src/main/kotlin/com/narareminder/mail/MailNotifier.kt`, `worker/src/main/kotlin/com/narareminder/alert/DiscordNotifier.kt` (`job.source.label` → `job.label`)
- Delete: `core/src/main/kotlin/com/narareminder/store/` (NotifiedNotice), PersistenceTest 의 발송 이력 테스트
- Test: `worker/src/test/kotlin/com/narareminder/matching/UserProfileTest.kt`, `worker/src/test/kotlin/com/narareminder/job/NoticeCollectorTest.kt`, `worker/src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt` (전체 교체)

**Interfaces:**
- Consumes: Task 2 엔티티·리포지토리, `Notice.key()`, `StoredNotice.of`
- Produces:
  - `enum JobName(val label: String, val source: NoticeSource?) { BID_POLLING("입찰공고 수집", BID), PRE_SPEC_DAILY("사전규격 수집", PRE_SPEC), DELIVERY("메일 발송", null) }`
  - `matching.UserProfile(userId: Long, filter: UserFilter, items: List<WatchItem>)`; `match(notice: Notice): List<WatchItem>`
  - `job.NoticeCollector.storeAndMatch(notices: List<Notice>, now: Instant): NoticeCollector.Result`; `data class Result(val newCount: Int, val deliveryCount: Int)`
  - `NoticePipeline(client, collector, jobStateRepository, mailNotifier, discordNotifier, jobRunRepository, props, clock)` — `run(job)` 시그니처는 그대로

- [ ] **Step 1: 실패하는 테스트 작성**

`worker/src/test/kotlin/com/narareminder/matching/UserProfileTest.kt`:

```kotlin
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
```

`worker/src/test/kotlin/com/narareminder/job/NoticeCollectorTest.kt`:

```kotlin
package com.narareminder.job

import com.narareminder.PostgresTestConfig
import com.narareminder.cadence.Cadence
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class NoticeCollectorTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
) {
    private val now = Instant.parse("2026-10-31T05:00:00Z")
    private val collector = NoticeCollector(notices, users, items, filters, deliveries, deliveryItems)

    private fun aUser(email: String, status: UserStatus = UserStatus.ACTIVE, filter: UserFilter.() -> Unit = {}): Long {
        val id = requireNotNull(users.save(AppUser(email, "hash", false, now, status)).id)
        filters.save(UserFilter(id).apply(filter))
        return id
    }

    private fun watch(userId: Long, type: WatchItemType, value: String): Long =
        requireNotNull(items.save(WatchItem(userId, type, value, Cadence.IMMEDIATE, now)).id)

    // 테스트 DB 에는 V5 이관 계정도 있으므로 사용자별로 검사한다
    private fun deliveriesOf(userId: Long) = deliveries.findAll().filter { it.userId == userId }

    @Test
    fun `새 공고가 항목에 걸리면 사용자별 발송 대기 1건과 걸린 항목들을 남긴다`() {
        val userId = aUser("a@example.com")
        val keywordId = watch(userId, WatchItemType.KEYWORD, "플랫폼")
        val agencyId = watch(userId, WatchItemType.AGENCY, "테스트기관")

        val result = collector.storeAndMatch(listOf(aNotice(noticeNo = "C1", title = "AI 플랫폼 구축", agency = "테스트기관")), now)

        assertThat(result.newCount).isEqualTo(1)
        val delivery = deliveriesOf(userId).single()
        assertThat(delivery.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(delivery.matchedAt).isEqualTo(now)
        assertThat(deliveryItems.findAllByIdDeliveryIdIn(listOf(delivery.requireId())).map { it.id.watchItemId })
            .containsExactlyInAnyOrder(keywordId, agencyId)
    }

    @Test
    fun `이미 저장된 공고는 다시 매칭하지 않는다`() {
        val userId = aUser("a@example.com")
        watch(userId, WatchItemType.KEYWORD, "플랫폼")
        val notice = aNotice(noticeNo = "C2", title = "AI 플랫폼 구축")
        collector.storeAndMatch(listOf(notice), now)

        val again = collector.storeAndMatch(listOf(notice, notice), now.plusSeconds(300))

        assertThat(again).isEqualTo(NoticeCollector.Result(0, 0))
        assertThat(deliveriesOf(userId)).hasSize(1)
    }

    @Test
    fun `정정공고(차수 증가)는 새 공고로 다시 매칭한다`() {
        val userId = aUser("a@example.com")
        watch(userId, WatchItemType.KEYWORD, "플랫폼")
        collector.storeAndMatch(listOf(aNotice(noticeNo = "C3", noticeOrd = "000", title = "AI 플랫폼 구축")), now)

        val result = collector.storeAndMatch(listOf(aNotice(noticeNo = "C3", noticeOrd = "001", title = "AI 플랫폼 구축")), now)

        assertThat(result.newCount).isEqualTo(1)
        assertThat(deliveriesOf(userId).map { it.notice.noticeOrd }).containsExactlyInAnyOrder("000", "001")
    }

    @Test
    fun `공통 조건에 걸리거나 항목이 없거나 비활성인 사용자에게는 매칭하지 않는다`() {
        val excluded = aUser("excluded@example.com") { update(listOf("구축"), NoticeCategory.entries, null, null) }
        watch(excluded, WatchItemType.KEYWORD, "플랫폼")
        val goodsOnly = aUser("goods@example.com") { update(emptyList(), listOf(NoticeCategory.GOODS), null, null) }
        watch(goodsOnly, WatchItemType.KEYWORD, "플랫폼")
        val noItems = aUser("empty@example.com")
        val disabled = aUser("disabled@example.com", UserStatus.DISABLED)
        watch(disabled, WatchItemType.KEYWORD, "플랫폼")

        collector.storeAndMatch(listOf(aNotice(noticeNo = "C4", title = "AI 플랫폼 구축", category = NoticeCategory.SERVICE)), now)

        listOf(excluded, goodsOnly, noItems, disabled).forEach { assertThat(deliveriesOf(it)).isEmpty() }
    }
}
```

`worker/src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt` (전체 교체):

```kotlin
package com.narareminder.job

import com.narareminder.alert.DiscordNotifier
import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.config.testProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.aNotice
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
import java.util.Optional

class NoticePipelineTest {

    // KST 2026-10-24 14:00
    private val now = Instant.parse("2026-10-24T05:00:00Z")
    private val client = mockk<NaraApiClient>()
    private val collector = mockk<NoticeCollector>()
    private val jobStateRepository = mockk<JobStateRepository>()
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val jobRunRepository = mockk<JobRunRepository>()
    private val state = JobState(JobName.BID_POLLING)
    private val runs = mutableListOf<JobRun>()

    private fun runBid(props: NaraProperties = testProperties()) = runBlocking {
        NoticePipeline(client, collector, jobStateRepository, mailNotifier, discordNotifier, jobRunRepository, props, Clock.fixed(now, KST))
            .run(JobName.BID_POLLING)
    }

    @BeforeEach
    fun setUp() {
        every { jobRunRepository.save(capture(runs)) } answers { firstArg() }
        every { jobStateRepository.findById(JobName.BID_POLLING) } returns Optional.of(state)
        every { jobStateRepository.save(any<JobState>()) } answers { firstArg() }
        coEvery { client.fetch(any(), any(), any(), any()) } returns emptyList()
        every { collector.storeAndMatch(any(), now) } returns NoticeCollector.Result(0, 0)
    }

    @Test
    fun `모든 업무구분을 조회해 저장·매칭에 넘기고 건수와 함께 이력을 남긴다`() {
        val notice = aNotice(noticeNo = "N1")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns listOf(notice)
        every { collector.storeAndMatch(listOf(notice), now) } returns NoticeCollector.Result(1, 2)

        runBid()

        coVerify(exactly = 4) { client.fetch(NoticeSource.BID, any(), any(), any()) }
        val run = runs.single()
        assertThat(run.status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(listOf(run.fetchedCount, run.matchedCount, run.sentCount)).containsExactly(1, 1, 2)
        assertThat(state.lastSuccessAt).isEqualTo(now)
    }

    @Test
    fun `한 업무구분 조회가 실패하면 나머지는 저장하고 실패로 남긴다`() {
        val notice = aNotice(noticeNo = "N1")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.GOODS, any(), any()) } throws IllegalStateException("HTTP 500")
        coEvery { client.fetch(NoticeSource.BID, NoticeCategory.SERVICE, any(), any()) } returns listOf(notice)
        every { collector.storeAndMatch(listOf(notice), now) } returns NoticeCollector.Result(1, 1)

        runBid()

        verify { collector.storeAndMatch(listOf(notice), now) }
        assertThat(runs.single().status).isEqualTo(JobRunStatus.FAILURE)
        assertThat(runs.single().error).contains("물품 조회 실패: HTTP 500")
        assertThat(state.lastSuccessAt).isNull()
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `저장·매칭이 실패하면 구간을 넘기지 않는다`() {
        every { collector.storeAndMatch(any(), now) } throws IllegalStateException("DB 오류")

        runBid()

        assertThat(runs.single().error).contains("공고 저장/매칭 실패: DB 오류")
        assertThat(state.lastSuccessAt).isNull()
    }

    @Test
    fun `연속 실패가 임계치에 닿으면 메일과 디스코드로 한 번 알린다`() {
        every { collector.storeAndMatch(any(), now) } throws IllegalStateException("DB 오류")

        runBid(testProperties(bidAlertAfterFailures = 1))

        verify(exactly = 1) { mailNotifier.sendAlert(JobName.BID_POLLING, 1, null, any()) }
        verify(exactly = 1) { discordNotifier.sendAlert(JobName.BID_POLLING, 1, null, any()) }
        assertThat(state.alertSent).isTrue()
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :worker:test --no-daemon`
Expected: 컴파일 실패 (`UserProfile`, `NoticeCollector` 없음, `NoticePipeline` 생성자 불일치)

- [ ] **Step 3: JobName 에 라벨과 발송 작업 추가**

`core/src/main/kotlin/com/narareminder/job/JobName.kt` (전체 교체):

```kotlin
package com.narareminder.job

import com.narareminder.notice.NoticeSource

enum class JobName(val label: String, val source: NoticeSource?) {
    BID_POLLING("입찰공고 수집", NoticeSource.BID),
    PRE_SPEC_DAILY("사전규격 수집", NoticeSource.PRE_SPEC),
    DELIVERY("메일 발송", null),
}
```

`worker/src/main/kotlin/com/narareminder/mail/MailNotifier.kt` 의 `sendAlert` 제목과 본문에서 `${job.source.label} 수집 실패` → `${job.label} 실패`, `작업: ${job.source.label}` → `작업: ${job.label}` 로 바꾼다. `worker/src/main/kotlin/com/narareminder/alert/DiscordNotifier.kt` 의 `${job.source.label} 수집 실패` 도 `${job.label} 실패` 로 바꾼다(출력 문자열은 "입찰공고 수집 실패" 로 이전과 같다).

- [ ] **Step 4: 매칭과 수집 저장 구현**

`worker/src/main/kotlin/com/narareminder/matching/UserProfile.kt`:

```kotlin
package com.narareminder.matching

import com.narareminder.notice.Notice
import com.narareminder.watch.UserFilter
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemType

data class UserProfile(val userId: Long, val filter: UserFilter, val items: List<WatchItem>) {

    private val excludeKeywords = filter.excludeKeywordList()
    private val categories = filter.categoryList().toSet()

    // 공통 조건은 AND, 항목(키워드·수요기관)은 OR 로 본다
    fun match(notice: Notice): List<WatchItem> {
        if (!passesCommon(notice)) return emptyList()
        return items.filter { it.matches(notice) }
    }

    private fun passesCommon(notice: Notice): Boolean =
        notice.category in categories &&
            excludeKeywords.none { notice.title.contains(it, ignoreCase = true) } &&
            passesPrice(notice.price)

    // 가격이 없는 공고는 누락 방지를 위해 통과시킨다
    private fun passesPrice(price: Long?): Boolean =
        price == null || ((filter.priceMin?.let { price >= it } ?: true) && (filter.priceMax?.let { price <= it } ?: true))

    private fun WatchItem.matches(notice: Notice): Boolean = when (type) {
        WatchItemType.KEYWORD -> notice.title.contains(value, ignoreCase = true)
        WatchItemType.AGENCY -> notice.agency?.contains(value, ignoreCase = true) ?: false
    }
}
```

`worker/src/main/kotlin/com/narareminder/job/NoticeCollector.kt`:

```kotlin
package com.narareminder.job

import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.matching.UserProfile
import com.narareminder.notice.Notice
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.key
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItemRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class NoticeCollector(
    private val noticeRepository: StoredNoticeRepository,
    private val userRepository: AppUserRepository,
    private val watchItemRepository: WatchItemRepository,
    private val userFilterRepository: UserFilterRepository,
    private val deliveryRepository: DeliveryRepository,
    private val deliveryItemRepository: DeliveryItemRepository,
) {
    data class Result(val newCount: Int, val deliveryCount: Int)

    // 공고 저장과 매칭을 한 트랜잭션으로 묶어, 실패하면 둘 다 없던 일이 되고 다음 실행이 다시 처리한다
    @Transactional
    fun storeAndMatch(notices: List<Notice>, now: Instant): Result {
        val unique = notices.distinctBy { it.key() }
        val existing = noticeRepository.findAllById(unique.map { it.key() }).map { it.id }.toSet()
        val fresh = unique.filterNot { it.key() in existing }
        if (fresh.isEmpty()) return Result(0, 0)

        noticeRepository.saveAll(fresh.map { StoredNotice.of(it, now) })
        val profiles = loadProfiles()
        var deliveryCount = 0
        for (notice in fresh) {
            for (profile in profiles) {
                val matched = profile.match(notice)
                if (matched.isEmpty()) continue
                val delivery = deliveryRepository.save(Delivery(profile.userId, notice.key(), now))
                deliveryItemRepository.saveAll(
                    matched.map { DeliveryItem(DeliveryItemId(delivery.requireId(), requireNotNull(it.id))) },
                )
                deliveryCount++
            }
        }
        return Result(fresh.size, deliveryCount)
    }

    private fun loadProfiles(): List<UserProfile> {
        val userIds = userRepository.findAllByStatus(UserStatus.ACTIVE).map { requireNotNull(it.id) }
        val itemsByUser = watchItemRepository.findAllByUserIdIn(userIds).groupBy { it.userId }
        val filters = userFilterRepository.findAllById(userIds).associateBy { it.userId }
        return userIds.mapNotNull { userId ->
            val items = itemsByUser[userId].orEmpty()
            val filter = filters[userId]
            if (items.isEmpty() || filter == null) null else UserProfile(userId, filter, items)
        }
    }
}
```

- [ ] **Step 5: 파이프라인 교체**

`worker/src/main/kotlin/com/narareminder/job/NoticePipeline.kt` (전체 교체):

```kotlin
package com.narareminder.job

import com.narareminder.alert.AlertMarkers
import com.narareminder.alert.DiscordNotifier
import com.narareminder.client.NaraApiClient
import com.narareminder.config.NaraProperties
import com.narareminder.mail.MailNotifier
import com.narareminder.notice.NoticeCategory
import com.narareminder.support.KST
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
    private val collector: NoticeCollector,
    private val jobStateRepository: JobStateRepository,
    private val mailNotifier: MailNotifier,
    private val discordNotifier: DiscordNotifier,
    private val jobRunRepository: JobRunRepository,
    private val props: NaraProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실패하면 lastSuccessAt 을 갱신하지 않아 다음 배치가 같은 구간을 다시 조회한다
    suspend fun run(job: JobName) {
        val source = checkNotNull(job.source) { "${job.label} 은 수집 작업이 아닙니다" }
        // JPA 블로킹 호출이 WebClient 이벤트 루프 스레드에서 실행되지 않도록 IO 디스패처로 옮긴다
        withContext(Dispatchers.IO) {
            val settings = props.settingsOf(job)
            val now = clock.instant()
            val state = jobStateRepository.findByIdOrNull(job) ?: JobState(job)
            val window = QueryWindow.of(state.lastSuccessAt, now, settings)
            val errors = mutableListOf<String>()

            // 업무구분은 사용자별 공통 조건이므로 수집은 전부 한다
            val fetched = NoticeCategory.entries.flatMap { category ->
                try {
                    client.fetch(source, category, window.from.toKst(), window.to.toKst())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${category.label} 조회 실패: ${e.message ?: e.javaClass.simpleName}"
                    emptyList()
                }
            }

            val result = try {
                collector.storeAndMatch(fetched, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "공고 저장/매칭 실패: ${e.message ?: e.javaClass.simpleName}"
                NoticeCollector.Result(0, 0)
            }

            if (errors.isEmpty()) {
                state.recordSuccess(now)
            } else {
                state.recordFailure()
                log.error(AlertMarkers.HANDLED_BY_JOB_ALERT, "[{}] 수집 실패 ({}회 연속): {}", job, state.consecutiveFailures, errors)
                alertIfNeeded(job, state, settings, errors)
            }
            recordRun(job, now, window, fetched.size, result, errors)
            try {
                jobStateRepository.save(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("[{}] 작업 상태 저장 실패", job, e)
                throw e
            }
        }
    }

    // 실행 이력은 조회용 부가 정보라 저장에 실패해도 수집 결과에는 영향을 주지 않는다
    private fun recordRun(
        job: JobName, startedAt: Instant, window: QueryWindow, fetchedCount: Int,
        result: NoticeCollector.Result, errors: List<String>,
    ) {
        try {
            jobRunRepository.save(
                JobRun(
                    jobName = job, startedAt = startedAt, finishedAt = clock.instant(),
                    windowFrom = window.from, windowTo = window.to,
                    fetchedCount = fetchedCount, matchedCount = result.newCount, sentCount = result.deliveryCount,
                    status = if (errors.isEmpty()) JobRunStatus.SUCCESS else JobRunStatus.FAILURE,
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ),
            )
        } catch (e: Exception) {
            log.error("[{}] 실행 이력 저장 실패", job, e)
        }
    }

    private fun alertIfNeeded(job: JobName, state: JobState, settings: NaraProperties.Job, errors: List<String>) {
        if (!state.shouldAlert(settings.alertAfterFailures)) return
        val message = errors.joinToString("\n")
        val mailSent = try {
            mailNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, message)
            true
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 메일 발송 실패", job, e)
            false
        }
        val discordSent = try {
            discordNotifier.sendAlert(job, state.consecutiveFailures, state.lastSuccessAt, message)
        } catch (e: Exception) {
            log.error("[{}] 에러 알림 디스코드 발송 실패", job, e)
            false
        }
        // 한 채널이라도 전달됐으면 같은 장애로 다시 알리지 않는다
        if (mailSent || discordSent) state.markAlertSent()
    }

    private fun NaraProperties.settingsOf(job: JobName): NaraProperties.Job = when (job) {
        JobName.BID_POLLING -> bid
        JobName.PRE_SPEC_DAILY -> preSpec
        JobName.DELIVERY -> error("발송은 수집 설정이 없습니다")
    }

    private fun Instant.toKst(): LocalDateTime = atZone(KST).toLocalDateTime()
}
```

- [ ] **Step 6: 쓰지 않게 된 발송 이력 엔티티 삭제**

```bash
git rm -q -r core/src/main/kotlin/com/narareminder/store
```

`core/src/test/kotlin/com/narareminder/store/PersistenceTest.kt` 에서 `발송 이력을 공고 키로 조회한다` 테스트, `notifiedNoticeRepository` 생성자 파라미터, `NoticeSource`/`aNotice`/`NotifiedNotice` 관련 import 를 지운다(작업 상태 테스트는 남긴다). 파일 패키지가 `com.narareminder.store` 이므로 `git mv core/src/test/kotlin/com/narareminder/store/PersistenceTest.kt core/src/test/kotlin/com/narareminder/job/JobStatePersistenceTest.kt` 로 옮기고 패키지를 `com.narareminder.job`, 클래스명을 `JobStatePersistenceTest` 로 바꾼다.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --no-daemon`
Expected: PASS. `MailNotifierTest`, `DiscordNotifierTest` 의 제목 기대값("입찰공고 수집 실패")도 그대로 통과한다.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(worker): 수집한 공고를 저장하고 사용자별 발송 대기로 매칭

공고 수집과 메일 발송을 분리해 사용자마다 다른 주기로 보내기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 6: 주기별 발송 (worker)

**Files:**
- Modify: `worker/src/main/kotlin/com/narareminder/config/NaraProperties.kt` (`Mail.to` 삭제), `worker/src/main/resources/application.yml` (`nara.mail.to` 삭제), `worker/src/test/resources/application-test.yml` (`nara.mail.to` 삭제), `worker/src/test/kotlin/com/narareminder/config/TestProperties.kt`, `worker/src/test/kotlin/com/narareminder/config/NaraPropertiesTest.kt`
- Modify: `worker/src/main/kotlin/com/narareminder/mail/MailNotifier.kt` (전체 교체), `worker/src/main/kotlin/com/narareminder/alert/DiscordNotifier.kt`, `worker/src/main/kotlin/com/narareminder/job/NoticePipeline.kt`
- Create: `worker/src/main/kotlin/com/narareminder/mail/MailQuota.kt`, `worker/src/main/kotlin/com/narareminder/delivery/DeliveryDispatcher.kt`
- Test: `worker/src/test/kotlin/com/narareminder/mail/MailNotifierTest.kt` (전체 교체), `worker/src/test/kotlin/com/narareminder/mail/MailQuotaTest.kt`, `worker/src/test/kotlin/com/narareminder/delivery/DeliveryDispatcherTest.kt`, `worker/src/test/kotlin/com/narareminder/alert/DiscordNotifierTest.kt`(추가), `worker/src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt`(수정)

**Interfaces:**
- Consumes: `Cadence.isDue`, Task 2 리포지토리(`DeliveryRepository.findAllByStatus`, `DeliveryItemRepository.findAllByIdDeliveryIdIn`, `JobRunRepository.sumSentCountSince`, `OutboxMailRepository.countByStatusAndSentAtGreaterThanEqual`, `AppUserRepository.findAdmins`)
- Produces:
  - `MailNotifier.sendDigest(to: String, notices: List<StoredNotice>, now: Instant)`, `sendHtml(to: String, subject: String, html: String)`, `sendAlert(to: List<String>, job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String)`
  - `DiscordNotifier.send(message: String): Boolean` (웹훅 없으면 false), `sendAlert(...)` 그대로
  - `MailQuota.check(now: Instant)` — 오늘(KST) 보낸 메일이 400통을 넘으면 하루 1번 디스코드 경고
  - `DeliveryDispatcher.dispatch()` — `@Scheduled(fixedDelay 1분, initialDelay 30초)`; 상수 `MAX_ATTEMPTS = 3`
  - `NoticePipeline` 생성자에 `userRepository: AppUserRepository` 추가(`mailNotifier` 다음). 배치 장애 메일은 관리자들에게 보낸다

- [ ] **Step 1: 설정에서 수신자 제거**

`NaraProperties.Mail` 을 다음으로 바꾼다:

```kotlin
    data class Mail(
        val from: String,
        val fromName: String = "나라장터 알림",
    )
```

`worker/src/main/resources/application.yml` 과 `worker/src/test/resources/application-test.yml` 의 `nara.mail.to:` 줄을 지운다. `TestProperties.kt` 의 `NaraProperties.Mail(from = "bot@example.com", to = listOf("me@example.com"))` → `NaraProperties.Mail(from = "bot@example.com")`. `NaraPropertiesTest` 에서 `"nara.mail.to" to "a@example.com,b@example.com",` 줄과 `assertThat(props.mail.to)...` 줄을 지우고 `assertThat(props.mail.fromName).isEqualTo("나라장터 알림")` 을 넣는다.

- [ ] **Step 2: 실패하는 테스트 작성**

`worker/src/test/kotlin/com/narareminder/mail/MailNotifierTest.kt` (전체 교체):

```kotlin
package com.narareminder.mail

import com.narareminder.config.testProperties
import com.narareminder.job.JobName
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.aNotice
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
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

    private fun stored(
        noticeNo: String = "N1",
        noticeOrd: String = "000",
        source: NoticeSource = NoticeSource.BID,
        title: String = "AI 플랫폼 구축",
        price: Long? = 100_000_000,
        agency: String? = "조달청",
        postedAt: LocalDateTime? = LocalDateTime.of(2026, 10, 24, 13, 0),
        deadlineAt: LocalDateTime? = LocalDateTime.of(2026, 11, 3, 10, 0),
    ) = StoredNotice.of(
        aNotice(noticeNo, noticeOrd, title, source, price = price, agency = agency, postedAt = postedAt, deadlineAt = deadlineAt),
        now,
    )

    @Test
    fun `모아 보내기 메일은 받는 사람·건수·KST 시각이 제목에 들어간다`() {
        notifier.sendDigest("a@example.com", listOf(stored("N1"), stored("N2")), now)

        assertThat(sent.captured.subject).isEqualTo("[나라장터] 새 공고 2건 (10/24 14:05)")
        val from = sent.captured.from.single() as InternetAddress
        assertThat(from.address).isEqualTo("bot@example.com")
        assertThat(from.personal).isEqualTo("나라장터 알림")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("a@example.com")
    }

    @Test
    fun `구분·정정 표시·가격·빈 값을 렌더링한다`() {
        notifier.sendDigest(
            "a@example.com",
            listOf(
                stored("N1", noticeOrd = "001", title = "AI 플랫폼", price = 150_000_000),
                stored("P1", noticeOrd = "0", source = NoticeSource.PRE_SPEC, title = "유지보수", price = null, agency = null, deadlineAt = null),
            ),
            now,
        )

        assertThat(html())
            .contains("<td>입찰공고</td>")
            .contains("<td>사전규격</td>")
            .contains("[정정] AI 플랫폼")
            .contains("150,000,000원")
            .contains("2026-11-03 10:00")
            .contains("https://www.g2b.go.kr/notice/N1")
            .contains("<td>-</td>")
            .contains("<th>추정가격/배정예산</th>")
    }

    @Test
    fun `공고명은 HTML 이스케이프되고 게시일 오름차순으로 정렬한다`() {
        notifier.sendDigest(
            "a@example.com",
            listOf(
                stored("LATE", title = "늦은 공고", postedAt = LocalDateTime.of(2026, 10, 24, 14, 0)),
                stored("EARLY", title = "A&B <script>", postedAt = LocalDateTime.of(2026, 10, 24, 13, 0)),
            ),
            now,
        )

        assertThat(html()).contains("A&amp;B &lt;script&gt;").doesNotContain("<script>")
        assertThat(html().indexOf("A&amp;B")).isLessThan(html().indexOf("늦은 공고"))
    }

    @Test
    fun `0건이면 발송하지 않는다`() {
        notifier.sendDigest("a@example.com", emptyList(), now)

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }

    @Test
    fun `인증 메일은 받은 HTML 을 그대로 보낸다`() {
        notifier.sendHtml("a@example.com", "[나라장터 알리미] 이메일 확인", "<p>링크</p>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알리미] 이메일 확인")
        assertThat(html()).isEqualTo("<p>링크</p>")
    }

    @Test
    fun `에러 알림 메일은 관리자들에게 작업명·연속 실패 횟수·오류를 보낸다`() {
        notifier.sendAlert(listOf("admin@example.com"), JobName.BID_POLLING, 6, Instant.parse("2026-10-24T04:30:00Z"), "용역 조회 실패: <키 오류>")

        assertThat(sent.captured.subject).isEqualTo("[나라장터 알림 서버] 입찰공고 수집 실패 (6회 연속)")
        assertThat(sent.captured.allRecipients.map { it.toString() }).containsExactly("admin@example.com")
        assertThat(html()).contains("2026-10-24 13:30").contains("용역 조회 실패: &lt;키 오류&gt;")
    }

    @Test
    fun `관리자가 없으면 에러 알림 메일을 보내지 않는다`() {
        notifier.sendAlert(emptyList(), JobName.PRE_SPEC_DAILY, 1, null, "오류")

        verify(exactly = 0) { mailSender.send(any<MimeMessage>()) }
    }
}
```

`worker/src/test/kotlin/com/narareminder/mail/MailQuotaTest.kt`:

```kotlin
package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.support.KST
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class MailQuotaTest {

    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    private val jobRuns = mockk<JobRunRepository>()
    private val outbox = mockk<OutboxMailRepository>()
    private val discord = mockk<DiscordNotifier> { every { send(any()) } returns true }
    private val quota = MailQuota(jobRuns, outbox, discord)

    private fun sentToday(day: String, alerts: Long, authMails: Long) {
        val since = kst("${day}T00:00:00")
        every { jobRuns.sumSentCountSince(JobName.DELIVERY, since) } returns alerts
        every { outbox.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, since) } returns authMails
    }

    @Test
    fun `400통 이하면 경고하지 않는다`() {
        sentToday("2026-10-31", 380, 20)

        quota.check(kst("2026-10-31T10:00:00"))

        verify(exactly = 0) { discord.send(any()) }
    }

    @Test
    fun `400통을 넘으면 하루에 한 번만 경고한다`() {
        sentToday("2026-10-31", 390, 11)

        quota.check(kst("2026-10-31T10:00:00"))
        quota.check(kst("2026-10-31T11:00:00"))

        verify(exactly = 1) { discord.send(match { it.contains("401통") }) }
    }

    @Test
    fun `다음 날 다시 넘으면 다시 경고한다`() {
        sentToday("2026-10-31", 401, 0)
        sentToday("2026-11-01", 401, 0)

        quota.check(kst("2026-10-31T10:00:00"))
        quota.check(kst("2026-11-01T10:00:00"))

        verify(exactly = 2) { discord.send(any()) }
    }
}
```

`worker/src/test/kotlin/com/narareminder/delivery/DeliveryDispatcherTest.kt`:

```kotlin
package com.narareminder.delivery

import com.narareminder.PostgresTestConfig
import com.narareminder.alert.DiscordNotifier
import com.narareminder.cadence.Cadence
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import com.narareminder.mail.MailNotifier
import com.narareminder.mail.MailQuota
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeKey
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class DeliveryDispatcherTest @Autowired constructor(
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
    private val jobRuns: JobRunRepository,
) {
    private fun kst(text: String): Instant = LocalDateTime.parse(text).atZone(KST).toInstant()

    // 2026-10-30(금) 14:03 KST 에 매칭된 공고들
    private val matchedAt = kst("2026-10-30T14:03:00")
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val mailQuota = mockk<MailQuota>(relaxed = true)

    private fun dispatchAt(now: Instant) =
        DeliveryDispatcher(deliveries, deliveryItems, items, notices, users, mailNotifier, discordNotifier, jobRuns, mailQuota, Clock.fixed(now, KST))
            .dispatch()

    private fun aUser(email: String): Long {
        val id = requireNotNull(users.save(AppUser(email, "hash", false, matchedAt)).id)
        filters.save(UserFilter(id))
        return id
    }

    private fun watch(userId: Long, value: String, cron: String): WatchItem =
        items.save(WatchItem(userId, WatchItemType.KEYWORD, value, cron, matchedAt))

    private fun pending(userId: Long, noticeNo: String, vararg watched: WatchItem): Delivery {
        val key = NoticeKey(NoticeSource.BID, noticeNo, "000")
        if (!notices.existsById(key)) {
            notices.save(StoredNotice(key, "공고 $noticeNo", NoticeCategory.SERVICE, 1_000, "조달청", null, null, "https://g2b/$noticeNo", matchedAt))
        }
        val delivery = deliveries.save(Delivery(userId, key, matchedAt))
        deliveryItems.saveAll(watched.map { DeliveryItem(DeliveryItemId(delivery.requireId(), requireNotNull(it.id))) })
        return delivery
    }

    private fun reload(delivery: Delivery): Delivery? = deliveries.findById(delivery.requireId()).orElse(null)

    private fun deliveryRuns() = jobRuns.findAll().filter { it.jobName == JobName.DELIVERY }

    @Test
    fun `주기가 오지 않은 공고는 보내지 않고 실행 이력도 남기지 않는다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "플랫폼", Cadence.IMMEDIATE))

        dispatchAt(kst("2026-10-30T14:04:30"))

        verify(exactly = 0) { mailNotifier.sendDigest(any(), any(), any()) }
        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(deliveryRuns()).isEmpty()
    }

    @Test
    fun `주기가 되면 사용자별로 한 통에 모아 보내고 SENT 로 바꾼다`() {
        val userId = aUser("a@example.com")
        val item = watch(userId, "플랫폼", Cadence.IMMEDIATE)
        val first = pending(userId, "D1", item)
        val second = pending(userId, "D2", item)
        val now = kst("2026-10-30T14:05:10")

        dispatchAt(now)

        verify(exactly = 1) { mailNotifier.sendDigest("a@example.com", match { it.size == 2 }, now) }
        assertThat(listOf(reload(first)?.status, reload(second)?.status)).containsOnly(DeliveryStatus.SENT)
        val run = deliveryRuns().single()
        assertThat(listOf(run.matchedCount, run.sentCount)).containsExactly(2, 1)
        assertThat(run.status).isEqualTo(JobRunStatus.SUCCESS)
        verify { mailQuota.check(now) }
    }

    @Test
    fun `여러 항목에 걸린 공고는 가장 빠른 주기로 나간다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "아침", "0 0 9 * * *"), watch(userId, "즉시", Cadence.IMMEDIATE))

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `판정을 건너뛰어도 나중 판정에서 보낸다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "아침", "0 0 9 * * *"))

        dispatchAt(kst("2026-10-31T15:00:00"))

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `주기를 바꾸면 다음 판정부터 바로 반영된다`() {
        val userId = aUser("a@example.com")
        val item = watch(userId, "아침", "0 0 9 * * *")
        val delivery = pending(userId, "D1", item)
        val now = kst("2026-10-30T14:05:10")
        dispatchAt(now)
        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.PENDING)

        item.cron = Cadence.IMMEDIATE
        items.save(item)
        dispatchAt(now)

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.SENT)
    }

    @Test
    fun `걸린 항목이 모두 지워진 대기 공고는 지운다`() {
        val userId = aUser("a@example.com")
        val orphan = pending(userId, "D1")

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(orphan)).isNull()
        verify(exactly = 0) { mailNotifier.sendDigest(any(), any(), any()) }
    }

    @Test
    fun `한 사람의 발송이 실패해도 다른 사람은 보낸다`() {
        val a = aUser("a@example.com")
        val b = aUser("b@example.com")
        val failing = pending(a, "D1", watch(a, "플랫폼", Cadence.IMMEDIATE))
        val ok = pending(b, "D1", watch(b, "플랫폼", Cadence.IMMEDIATE))
        every { mailNotifier.sendDigest("a@example.com", any(), any()) } throws IllegalStateException("SMTP 오류")

        dispatchAt(kst("2026-10-30T14:05:10"))

        assertThat(reload(failing)?.status).isEqualTo(DeliveryStatus.PENDING)
        assertThat(reload(failing)?.attempts).isEqualTo(1)
        assertThat(reload(ok)?.status).isEqualTo(DeliveryStatus.SENT)
        val run = deliveryRuns().single()
        assertThat(run.status).isEqualTo(JobRunStatus.FAILURE)
        assertThat(run.error).contains("a@example.com 발송 실패: SMTP 오류")
    }

    @Test
    fun `3번 실패하면 FAILED 로 바꾸고 디스코드로 한 번 알린다`() {
        val userId = aUser("a@example.com")
        val delivery = pending(userId, "D1", watch(userId, "플랫폼", Cadence.IMMEDIATE))
        every { mailNotifier.sendDigest(any(), any(), any()) } throws IllegalStateException("SMTP 오류")
        val now = kst("2026-10-30T14:05:10")

        repeat(3) { dispatchAt(now) }

        assertThat(reload(delivery)?.status).isEqualTo(DeliveryStatus.FAILED)
        verify(exactly = 1) { discordNotifier.send(match { it.contains("a@example.com") }) }
    }
}
```

`worker/src/test/kotlin/com/narareminder/alert/DiscordNotifierTest.kt` 에 테스트를 추가한다:

```kotlin
    @Test
    fun `임의 메시지를 그대로 보내고 웹훅이 없으면 false`() {
        server.enqueue(MockResponse().setResponseCode(204))

        assertThat(notifier(server.url("/hook").toString()).send("⚠️ 경고")).isTrue()
        assertThat(server.takeRequest().body.readUtf8()).contains("⚠️ 경고")
        assertThat(notifier("").send("⚠️ 경고")).isFalse()
    }
```

`worker/src/test/kotlin/com/narareminder/job/NoticePipelineTest.kt` 를 수정한다:
- import 추가: `com.narareminder.user.AppUser`, `com.narareminder.user.AppUserRepository`
- 필드 추가: `private val userRepository = mockk<AppUserRepository> { every { findAdmins() } returns listOf(AppUser("admin@example.com", "hash", true, Instant.EPOCH)) }`
- `NoticePipeline(...)` 생성자 호출을 `NoticePipeline(client, collector, jobStateRepository, mailNotifier, userRepository, discordNotifier, jobRunRepository, props, Clock.fixed(now, KST))` 로 바꾼다
- 알림 테스트의 검증을 `verify(exactly = 1) { mailNotifier.sendAlert(listOf("admin@example.com"), JobName.BID_POLLING, 1, null, any()) }` 로 바꾼다

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :worker:test --no-daemon`
Expected: 컴파일 실패 (`sendDigest`, `MailQuota`, `DeliveryDispatcher`, `DiscordNotifier.send` 없음)

- [ ] **Step 4: MailNotifier 교체**

`worker/src/main/kotlin/com/narareminder/mail/MailNotifier.kt` (전체 교체):

```kotlin
package com.narareminder.mail

import com.narareminder.config.NaraProperties
import com.narareminder.job.JobName
import com.narareminder.notice.StoredNotice
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
    // 한 사람의 발송 시점에 쌓인 공고를 한 통으로 보낸다
    fun sendDigest(to: String, notices: List<StoredNotice>, now: Instant) {
        if (notices.isEmpty()) return
        val subject = "[나라장터] 새 공고 ${notices.size}건 (${now.atZone(KST).format(SUBJECT_DATE_TIME)})"
        send(listOf(to), subject, noticeTable(notices))
    }

    fun sendHtml(to: String, subject: String, html: String) {
        send(listOf(to), subject, html)
    }

    fun sendAlert(to: List<String>, job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String) {
        if (to.isEmpty()) return
        val subject = "[나라장터 알림 서버] ${job.label} 실패 (${consecutiveFailures}회 연속)"
        val html = """
            <p>작업: ${job.label}</p>
            <p>연속 실패: ${consecutiveFailures}회</p>
            <p>마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}</p>
            <pre>${escape(error)}</pre>
        """.trimIndent()
        send(to, subject, html)
    }

    private fun noticeTable(notices: List<StoredNotice>): String {
        val rows = notices.sortedWith(compareBy({ it.id.source }, { it.postedAt })).joinToString("\n") { notice ->
            val title = escape((if (notice.isRevision) "[정정] " else "") + notice.title)
            val link = notice.detailUrl?.let { "<a href=\"${escape(it)}\">$title</a>" } ?: title
            """
            <tr>
              <td>${notice.id.source.label}</td>
              <td>$link<br><small>${escape(notice.id.noticeNo)}</small></td>
              <td>${notice.category?.label ?: "-"}</td>
              <td>${escape(notice.agency ?: "-")}</td>
              <td>${notice.price?.let { "%,d원".format(it) } ?: "-"}</td>
              <td>${notice.deadlineAt?.format(DISPLAY_DATE_TIME) ?: "-"}</td>
            </tr>
            """.trimIndent()
        }
        return """
            <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>구분</th><th>공고명</th><th>업무구분</th><th>수요기관</th><th>추정가격/배정예산</th><th>마감일시</th></tr>
            $rows
            </table>
        """.trimIndent()
    }

    private fun send(to: List<String>, subject: String, html: String) {
        val message = mailSender.createMimeMessage()
        MimeMessageHelper(message, false, "UTF-8").apply {
            setFrom(props.mail.from, props.mail.fromName)
            setTo(to.toTypedArray())
            setSubject(subject)
            setText(html, true)
        }
        mailSender.send(message)
    }

    private fun escape(text: String): String = HtmlUtils.htmlEscape(text, "UTF-8")

    companion object {
        private val SUBJECT_DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
```

- [ ] **Step 5: 디스코드 메시지 일반화, 파이프라인 알림 수신자 변경**

`worker/src/main/kotlin/com/narareminder/alert/DiscordNotifier.kt` 의 클래스 본문을 다음으로 바꾼다:

```kotlin
@Component
class DiscordNotifier(props: NaraProperties) {

    private val webhook = props.discord.webhookUrl.takeIf { it.isNotBlank() }?.let { DiscordWebhook(it) }

    // 웹훅이 설정되지 않았으면 보내지 않고 false 를 돌려준다
    fun send(message: String): Boolean {
        val hook = webhook ?: return false
        hook.send(message)
        return true
    }

    fun sendAlert(job: JobName, consecutiveFailures: Int, lastSuccessAt: Instant?, error: String): Boolean =
        send(
            "🚨 **[나라장터 알림 서버] ${job.label} 실패 (${consecutiveFailures}회 연속)**\n" +
                "마지막 성공: ${lastSuccessAt?.atZone(KST)?.format(DISPLAY_DATE_TIME) ?: "없음"}\n" +
                "```\n$error\n```",
        )

    companion object {
        private val DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
```

`NoticePipeline` 생성자에 `private val userRepository: AppUserRepository,` 를 `mailNotifier` 다음에 추가하고(import `com.narareminder.user.AppUserRepository`), `alertIfNeeded` 의 메일 호출을 다음으로 바꾼다:

```kotlin
            mailNotifier.sendAlert(userRepository.findAdmins().map { it.email }, job, state.consecutiveFailures, state.lastSuccessAt, message)
```

- [ ] **Step 6: MailQuota 와 DeliveryDispatcher 구현**

`worker/src/main/kotlin/com/narareminder/mail/MailQuota.kt`:

```kotlin
package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import com.narareminder.job.JobName
import com.narareminder.job.JobRunRepository
import com.narareminder.support.KST
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

// Gmail 은 하루 약 500명까지만 보낼 수 있어 한도 전에 관리자에게 알린다
@Component
class MailQuota(
    private val jobRunRepository: JobRunRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val discordNotifier: DiscordNotifier,
) {
    private var warnedOn: LocalDate? = null

    @Synchronized
    fun check(now: Instant) {
        val today = now.atZone(KST).toLocalDate()
        if (warnedOn == today) return
        val since = today.atStartOfDay(KST).toInstant()
        val sent = jobRunRepository.sumSentCountSince(JobName.DELIVERY, since) +
            outboxMailRepository.countByStatusAndSentAtGreaterThanEqual(OutboxStatus.SENT, since)
        if (sent <= WARN_THRESHOLD) return
        if (discordNotifier.send("⚠️ **[나라장터 알림 서버] 오늘 메일 ${sent}통 발송** — Gmail 하루 한도(약 500통)에 가깝습니다")) {
            warnedOn = today
        }
    }

    companion object {
        private const val WARN_THRESHOLD = 400
    }
}
```

`worker/src/main/kotlin/com/narareminder/delivery/DeliveryDispatcher.kt`:

```kotlin
package com.narareminder.delivery

import com.narareminder.alert.DiscordNotifier
import com.narareminder.cadence.Cadence
import com.narareminder.job.JobName
import com.narareminder.job.JobRun
import com.narareminder.job.JobRunRepository
import com.narareminder.job.JobRunStatus
import com.narareminder.mail.MailNotifier
import com.narareminder.mail.MailQuota
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.WatchItemRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class DeliveryDispatcher(
    private val deliveryRepository: DeliveryRepository,
    private val deliveryItemRepository: DeliveryItemRepository,
    private val watchItemRepository: WatchItemRepository,
    private val noticeRepository: StoredNoticeRepository,
    private val userRepository: AppUserRepository,
    private val mailNotifier: MailNotifier,
    private val discordNotifier: DiscordNotifier,
    private val jobRunRepository: JobRunRepository,
    private val mailQuota: MailQuota,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    fun dispatch() {
        val now = clock.instant()
        val pending = deliveryRepository.findAllByStatus(DeliveryStatus.PENDING)
        if (pending.isEmpty()) return

        val itemIdsByDelivery = deliveryItemRepository.findAllByIdDeliveryIdIn(pending.map { it.requireId() })
            .groupBy({ it.id.deliveryId }, { it.id.watchItemId })
        val cronByItem = watchItemRepository.findAllById(itemIdsByDelivery.values.flatten().distinct())
            .associate { requireNotNull(it.id) to it.cron }

        // 걸린 항목이 모두 삭제된 공고는 보낼 기준이 없으므로 지운다
        val (orphans, live) = pending.partition { itemIdsByDelivery[it.requireId()].isNullOrEmpty() }
        if (orphans.isNotEmpty()) deliveryRepository.deleteAll(orphans)

        val due = live.filter { delivery ->
            val crons = itemIdsByDelivery.getValue(delivery.requireId()).mapNotNull { cronByItem[it] }
            Cadence.isDue(crons, delivery.matchedAt, now)
        }
        if (due.isNotEmpty()) send(due, now)
    }

    private fun send(due: List<Delivery>, now: Instant) {
        val emails = userRepository.findAllById(due.map { it.userId }.distinct()).associate { requireNotNull(it.id) to it.email }
        val notices = noticeRepository.findAllById(due.map { it.notice }.distinct()).associateBy { it.id }
        val errors = mutableListOf<String>()
        val exhausted = mutableListOf<String>()
        var mailCount = 0
        var noticeCount = 0

        due.groupBy { it.userId }.forEach { (userId, deliveries) ->
            val email = emails[userId] ?: return@forEach
            try {
                mailNotifier.sendDigest(email, deliveries.mapNotNull { notices[it.notice] }, now)
                deliveries.forEach { it.markSent(now) }
                mailCount++
                noticeCount += deliveries.size
            } catch (e: Exception) {
                log.warn("[DELIVERY] {} 메일 발송 실패", email, e)
                errors += "$email 발송 실패: ${e.message ?: e.javaClass.simpleName}"
                if (deliveries.map { it.recordFailure(MAX_ATTEMPTS) }.any { it }) exhausted += email
            }
        }
        deliveryRepository.saveAll(due)
        recordRun(now, noticeCount, mailCount, errors)
        if (exhausted.isNotEmpty()) alertExhausted(exhausted, errors)
        if (mailCount > 0) mailQuota.check(now)
    }

    private fun recordRun(startedAt: Instant, noticeCount: Int, mailCount: Int, errors: List<String>) {
        jobRunRepository.save(
            JobRun(
                jobName = JobName.DELIVERY, startedAt = startedAt, finishedAt = clock.instant(),
                windowFrom = startedAt, windowTo = startedAt,
                fetchedCount = 0, matchedCount = noticeCount, sentCount = mailCount,
                status = if (errors.isEmpty()) JobRunStatus.SUCCESS else JobRunStatus.FAILURE,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
        )
    }

    private fun alertExhausted(emails: List<String>, errors: List<String>) {
        try {
            discordNotifier.send(
                "📭 **[나라장터 알림 서버] 메일 발송 ${MAX_ATTEMPTS}회 실패** ${emails.joinToString()}\n```\n${errors.joinToString("\n")}\n```",
            )
        } catch (e: Exception) {
            log.warn("[DELIVERY] 발송 실패 디스코드 알림 실패", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :worker:test --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(worker): 항목별 주기에 맞춰 사용자별로 모아 보내기

1분마다 발송 대상을 판정해 사용자당 한 통으로 보내고, 실패·Gmail 한도는 디스코드로 알리기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: 인증 메일 발송과 보관 기간 정리 (worker)

**Files:**
- Create: `worker/src/main/kotlin/com/narareminder/mail/OutboxSender.kt`, `worker/src/main/kotlin/com/narareminder/job/RetentionCleanup.kt`
- Delete: `worker/src/main/kotlin/com/narareminder/job/JobRunCleanup.kt`, `worker/src/test/kotlin/com/narareminder/job/JobRunCleanupTest.kt`
- Test: `worker/src/test/kotlin/com/narareminder/mail/OutboxSenderTest.kt`, `worker/src/test/kotlin/com/narareminder/job/RetentionCleanupTest.kt`

**Interfaces:**
- Consumes: `OutboxMailRepository`, `MailNotifier.sendHtml`, `MailQuota.check`, `DiscordNotifier.send`, `StoredNoticeRepository.deleteCollectedBefore`, `EmailTokenRepository.deleteUsedOrExpired`, `OutboxMailRepository.deleteByStatusNotAndCreatedAtBefore`, `JobRunRepository.deleteByStartedAtBefore`
- Produces: `OutboxSender.send()` (`@Scheduled` 10초), `RetentionCleanup.purge(): RetentionCleanup.Result` (`@Scheduled` 03:00 KST), `data class Result(val notices: Int, val runs: Int, val tokens: Int, val outbox: Long)`

- [ ] **Step 1: 실패하는 테스트 작성**

`worker/src/test/kotlin/com/narareminder/mail/OutboxSenderTest.kt`:

```kotlin
package com.narareminder.mail

import com.narareminder.PostgresTestConfig
import com.narareminder.alert.DiscordNotifier
import com.narareminder.support.KST
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
class OutboxSenderTest @Autowired constructor(private val outbox: OutboxMailRepository) {

    private val now = Instant.parse("2026-10-31T05:00:00Z")
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val mailQuota = mockk<MailQuota>(relaxed = true)
    private val sender = OutboxSender(outbox, mailNotifier, discordNotifier, mailQuota, Clock.fixed(now, KST))

    @Test
    fun `대기 중인 인증 메일을 보내고 SENT 로 바꾼다`() {
        val mail = outbox.save(OutboxMail("a@example.com", "이메일 확인", "<p>링크</p>", now))

        sender.send()

        verify { mailNotifier.sendHtml("a@example.com", "이메일 확인", "<p>링크</p>") }
        val saved = outbox.findById(requireNotNull(mail.id)).orElseThrow()
        assertThat(saved.status).isEqualTo(OutboxStatus.SENT)
        assertThat(saved.sentAt).isEqualTo(now)
        verify { mailQuota.check(now) }
    }

    @Test
    fun `3번 실패하면 FAILED 로 바꾸고 디스코드로 알린다`() {
        val mail = outbox.save(OutboxMail("a@example.com", "이메일 확인", "<p>링크</p>", now))
        every { mailNotifier.sendHtml(any(), any(), any()) } throws IllegalStateException("SMTP 오류")

        repeat(3) { sender.send() }

        assertThat(outbox.findById(requireNotNull(mail.id)).orElseThrow().status).isEqualTo(OutboxStatus.FAILED)
        verify(exactly = 1) { discordNotifier.send(match { it.contains("a@example.com") }) }
        verify(exactly = 0) { mailQuota.check(any()) }
    }
}
```

`worker/src/test/kotlin/com/narareminder/job/RetentionCleanupTest.kt`:

```kotlin
package com.narareminder.job

import com.narareminder.PostgresTestConfig
import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.mail.OutboxMail
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.notice.aNotice
import com.narareminder.notice.key
import com.narareminder.support.KST
import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.TokenPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class RetentionCleanupTest @Autowired constructor(
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val users: AppUserRepository,
    private val jobRuns: JobRunRepository,
    private val tokens: EmailTokenRepository,
    private val outbox: OutboxMailRepository,
    private val em: TestEntityManager,
) {
    private val now = Instant.parse("2026-10-31T00:00:00Z")
    private val old = now.minus(Duration.ofDays(91))
    private val recent = now.minus(Duration.ofDays(1))
    private val cleanup = RetentionCleanup(notices, jobRuns, tokens, outbox, Clock.fixed(now, KST))

    private fun run(at: Instant) = JobRun(
        jobName = JobName.BID_POLLING, startedAt = at, finishedAt = at, windowFrom = at, windowTo = at,
        fetchedCount = 0, matchedCount = 0, sentCount = 0, status = JobRunStatus.SUCCESS, error = null,
    )

    private fun token(hash: String, expiresAt: Instant, usedAt: Instant? = null) =
        EmailToken("a@example.com", TokenPurpose.RESET, hash, null, null, expiresAt, old, usedAt)

    @Test
    fun `90일 지난 공고·발송 대기·실행 이력과 쓴·만료된 토큰, 끝난 지 7일 지난 outbox 를 지운다`() {
        val userId = requireNotNull(users.save(AppUser("a@example.com", "hash", false, old)).id)
        val oldNotice = notices.save(StoredNotice.of(aNotice(noticeNo = "OLD"), old))
        notices.save(StoredNotice.of(aNotice(noticeNo = "NEW"), recent))
        val oldDelivery = deliveries.save(Delivery(userId, oldNotice.id, old))
        jobRuns.saveAll(listOf(run(old), run(recent)))
        tokens.saveAll(listOf(token("used", now.plusSeconds(60), usedAt = recent), token("expired", recent), token("valid", now.plusSeconds(60))))
        outbox.save(OutboxMail("a@example.com", "옛 메일", "<p/>", old).apply { markSent(old) })
        outbox.save(OutboxMail("a@example.com", "대기", "<p/>", old))
        em.flush()
        em.clear()

        val result = cleanup.purge()
        em.clear()

        assertThat(result).isEqualTo(RetentionCleanup.Result(notices = 1, runs = 1, tokens = 2, outbox = 1))
        assertThat(notices.findById(aNotice(noticeNo = "OLD").key())).isEmpty()
        assertThat(notices.findById(aNotice(noticeNo = "NEW").key())).isPresent()
        assertThat(deliveries.findById(oldDelivery.requireId())).isEmpty()
        assertThat(tokens.findAll().map { it.tokenHash }).containsExactly("valid")
        assertThat(outbox.findAll().map { it.subject }).containsExactly("대기")
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :worker:test --tests '*OutboxSenderTest' --tests '*RetentionCleanupTest' --no-daemon`
Expected: 컴파일 실패 (`OutboxSender`, `RetentionCleanup` 없음)

- [ ] **Step 3: 구현**

`worker/src/main/kotlin/com/narareminder/mail/OutboxSender.kt`:

```kotlin
package com.narareminder.mail

import com.narareminder.alert.DiscordNotifier
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

// 가입 확인·비밀번호 재설정 링크는 바로 받아야 하므로 10초마다 보낸다
@Component
class OutboxSender(
    private val outboxMailRepository: OutboxMailRepository,
    private val mailNotifier: MailNotifier,
    private val discordNotifier: DiscordNotifier,
    private val mailQuota: MailQuota,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT15S")
    fun send() {
        val batch = outboxMailRepository.findTop20ByStatusOrderByIdAsc(OutboxStatus.PENDING)
        if (batch.isEmpty()) return
        val now = clock.instant()
        var sent = 0
        batch.forEach { mail ->
            try {
                mailNotifier.sendHtml(mail.toAddress, mail.subject, mail.html)
                mail.markSent(now)
                sent++
            } catch (e: Exception) {
                log.warn("[OUTBOX] {} 메일 발송 실패", mail.toAddress, e)
                if (mail.recordFailure(MAX_ATTEMPTS)) alertExhausted(mail, e)
            }
        }
        outboxMailRepository.saveAll(batch)
        if (sent > 0) mailQuota.check(now)
    }

    private fun alertExhausted(mail: OutboxMail, cause: Exception) {
        try {
            discordNotifier.send(
                "📭 **[나라장터 알림 서버] 인증 메일 ${MAX_ATTEMPTS}회 실패** ${mail.toAddress} / ${mail.subject}\n" +
                    "```\n${cause.message ?: cause.javaClass.simpleName}\n```",
            )
        } catch (e: Exception) {
            log.warn("[OUTBOX] 발송 실패 디스코드 알림 실패", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
```

`worker/src/main/kotlin/com/narareminder/job/RetentionCleanup.kt`:

```kotlin
package com.narareminder.job

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.mail.OutboxStatus
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.user.EmailTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class RetentionCleanup(
    private val noticeRepository: StoredNoticeRepository,
    private val jobRunRepository: JobRunRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val notices: Int, val runs: Int, val tokens: Int, val outbox: Long)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    fun purge(): Result {
        val now = clock.instant()
        val cutoff = now.minus(RETENTION)
        val result = Result(
            notices = noticeRepository.deleteCollectedBefore(cutoff),
            runs = jobRunRepository.deleteByStartedAtBefore(cutoff),
            tokens = emailTokenRepository.deleteUsedOrExpired(now),
            outbox = outboxMailRepository.deleteByStatusNotAndCreatedAtBefore(OutboxStatus.PENDING, now.minus(OUTBOX_RETENTION)),
        )
        log.info("보관 기간 정리: {}", result)
        return result
    }

    companion object {
        private val RETENTION: Duration = Duration.ofDays(90)
        private val OUTBOX_RETENTION: Duration = Duration.ofDays(7)
    }
}
```

```bash
git rm -q worker/src/main/kotlin/com/narareminder/job/JobRunCleanup.kt worker/src/test/kotlin/com/narareminder/job/JobRunCleanupTest.kt
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :worker:test --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(worker): 인증 메일 outbox 발송과 보관 기간 정리 추가

api 가 SMTP 비밀번호 없이 가입·재설정 메일을 보내게 하고, 쌓이는 공고·토큰을 정리하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 8: 로그인·세션·CSRF·관리자 부트스트랩 (api)

기존 인증 없는 필터 API 를 없애고(새 항목·공통 조건 API 는 Task 10), 모든 `/api/**` 에 로그인을 요구한다. 실행 이력은 `/api/admin/runs` 로 옮긴다.

**Files:**
- Modify: `api/build.gradle.kts`, `api/src/main/kotlin/com/narareminder/ApiApplication.kt`, `api/src/main/resources/application.yml`, `core/src/main/kotlin/com/narareminder/web/ApiException.kt`
- Create: `api/src/main/kotlin/com/narareminder/config/AppProperties.kt`
- Create: `api/src/main/kotlin/com/narareminder/security/{AppPrincipal,SecurityConfig,CsrfCookieFilter}.kt`
- Create: `api/src/main/kotlin/com/narareminder/auth/{Credentials,AuthExceptions,AuthDtos,AccountService,AuthService,AdminBootstrap,AuthController}.kt`
- Move: `api/src/main/kotlin/com/narareminder/job/JobRunController.kt` → `api/src/main/kotlin/com/narareminder/admin/JobRunController.kt`, `api/src/test/kotlin/com/narareminder/job/JobRunApiTest.kt` → `api/src/test/kotlin/com/narareminder/admin/JobRunApiTest.kt`
- Delete: `core/src/main/kotlin/com/narareminder/filter/`, `core/src/main/kotlin/com/narareminder/notice/{FilterCriteria,NoticeFilter}.kt`, `api/src/main/kotlin/com/narareminder/filter/`, `core/src/test/kotlin/com/narareminder/filter/`, `core/src/test/kotlin/com/narareminder/notice/NoticeFilterTest.kt`, `api/src/test/kotlin/com/narareminder/filter/`
- Test: `api/src/test/kotlin/com/narareminder/support/TestAuth.kt`, `api/src/test/kotlin/com/narareminder/auth/AuthApiTest.kt`, `api/src/test/kotlin/com/narareminder/auth/AdminBootstrapTest.kt`

**Interfaces:**
- Consumes: `AppUser`, `AppUserRepository`, `UserFilter`, `UserFilterRepository`
- Produces:
  - `web.BadRequestException(code: String, message: String)`, `web.ConflictException(code: String, message: String)` (core)
  - `config.AppProperties(adminEmail: String = "", baseUrl: String = "http://localhost:5173")` — prefix `app`
  - `security.AppPrincipal(userId: Long, email: String, admin: Boolean) : Serializable`; `authorities()`; `toAuthentication()`. 컨트롤러는 `@AuthenticationPrincipal principal: AppPrincipal` 로 받는다
  - `auth.normalizeEmail(raw: String): String`(형식 검사 포함, 400 `INVALID_EMAIL`), `auth.validatePassword(password: String)`(8~72자, 400 `INVALID_PASSWORD`)
  - `auth.AccountService.create(email: String, passwordHash: String?, admin: Boolean): AppUser` — 기본 `UserFilter` 도 만든다
  - `auth.AuthService.authenticate(rawEmail: String, password: String): AppPrincipal`
  - `auth.AuthController` — `POST /api/auth/login` → `MeResponse(email, admin)`, `GET /api/me`, `POST /api/auth/logout`(Spring Security 처리, 200 빈 본문)
  - 예외: `InvalidCredentialsException`(401 `INVALID_CREDENTIALS`), `AccountLockedException`(423 `ACCOUNT_LOCKED`), `InvalidInviteException`(400 `INVALID_INVITE`), `AlreadyRegisteredException`(409 `ALREADY_REGISTERED`), `InvalidTokenException`(400 `INVALID_TOKEN`)
  - 테스트 도우미 `support.authAs(userId: Long, email: String = "user$userId@example.com", admin: Boolean = false): RequestPostProcessor`
  - 권한: `/api/auth/**` 공개, `/api/admin/**` 관리자, 나머지 로그인. 미인증 401, 권한 없음 403, CSRF 없는 변경 요청 403

- [ ] **Step 1: 기존 필터 API 삭제, 실행 이력 이동**

```bash
git rm -q -r core/src/main/kotlin/com/narareminder/filter api/src/main/kotlin/com/narareminder/filter \
  core/src/test/kotlin/com/narareminder/filter api/src/test/kotlin/com/narareminder/filter \
  core/src/main/kotlin/com/narareminder/notice/FilterCriteria.kt core/src/main/kotlin/com/narareminder/notice/NoticeFilter.kt \
  core/src/test/kotlin/com/narareminder/notice/NoticeFilterTest.kt
mkdir -p api/src/main/kotlin/com/narareminder/admin api/src/test/kotlin/com/narareminder/admin
git mv api/src/main/kotlin/com/narareminder/job/JobRunController.kt api/src/main/kotlin/com/narareminder/admin/JobRunController.kt
git mv api/src/test/kotlin/com/narareminder/job/JobRunApiTest.kt api/src/test/kotlin/com/narareminder/admin/JobRunApiTest.kt
```

`admin/JobRunController.kt`: 패키지를 `com.narareminder.admin` 으로, `@RequestMapping("/api/runs")` → `@RequestMapping("/api/admin/runs")`, import 에 `com.narareminder.job.JobName`, `com.narareminder.job.JobRun`, `com.narareminder.job.JobRunRepository`, `com.narareminder.job.JobRunStatus` 를 추가한다.

`admin/JobRunApiTest.kt`: 패키지를 `com.narareminder.admin` 으로 바꾸고 위 job 클래스 import 를 추가한다. 모든 `"/api/runs` 를 `"/api/admin/runs` 로 바꾸고, 클래스에 `@WithMockUser(roles = ["ADMIN"])`(import `org.springframework.security.test.context.support.WithMockUser`)를 붙이고, 다음 테스트를 추가한다:

```kotlin
    @Test
    @WithMockUser(roles = ["USER"])
    fun `관리자가 아니면 403`() {
        mockMvc.get("/api/admin/runs").andExpect { status { isForbidden() } }
    }
```

- [ ] **Step 2: 의존성과 설정**

`api/build.gradle.kts` 의 dependencies 에 추가:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
```

`ApiApplication.kt` 의 애노테이션을 다음으로 바꾼다(사용자 저장소를 직접 다루므로 Boot 의 기본 사용자/임시 비밀번호를 만들지 않는다):

```kotlin
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
```

(import `org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration`)

`api/src/main/resources/application.yml` 의 `server:` 블록을 다음으로 바꾸고 맨 아래 `app:` 블록을 추가한다:

```yaml
server:
  port: 8080
  # Caddy 가 붙이는 X-Forwarded-Proto 로 https 를 인식해 세션·CSRF 쿠키에 Secure 를 붙인다
  forward-headers-strategy: native
  error:
    include-stacktrace: never
  servlet:
    session:
      timeout: 8h
      cookie:
        http-only: true
        same-site: lax

app:
  admin-email: ${ADMIN_EMAIL:}
  base-url: ${APP_BASE_URL:http://localhost:5173}
```

`core/src/main/kotlin/com/narareminder/web/ApiException.kt` 에 추가:

```kotlin
class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)

class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)
```

- [ ] **Step 3: 실패하는 테스트 작성**

`api/src/test/kotlin/com/narareminder/support/TestAuth.kt`:

```kotlin
package com.narareminder.support

import com.narareminder.security.AppPrincipal
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.RequestPostProcessor

fun authAs(userId: Long, email: String = "user$userId@example.com", admin: Boolean = false): RequestPostProcessor =
    authentication(AppPrincipal(userId, email, admin).toAuthentication())
```

`api/src/test/kotlin/com/narareminder/auth/AuthApiTest.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.support.authAs
import com.narareminder.user.AppUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class AuthApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    private fun member(email: String = "member@example.com", password: String? = "correct-horse") =
        accountService.create(email, password?.let { passwordEncoder.encode(it) }, admin = false)

    private fun login(email: String, password: String, session: MockHttpSession = MockHttpSession()): ResultActionsDsl =
        mockMvc.post("/api/auth/login") {
            this.session = session
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }

    @Test
    fun `로그인하면 세션에 인증이 저장되고 내 정보를 돌려준다`() {
        member()
        val session = MockHttpSession()

        login("member@example.com", "correct-horse", session).andExpect {
            status { isOk() }
            jsonPath("$.email") { value("member@example.com") }
            jsonPath("$.admin") { value(false) }
        }
        mockMvc.get("/api/me") { this.session = session }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("member@example.com") }
        }
    }

    @Test
    fun `이메일 대소문자와 앞뒤 공백은 무시한다`() {
        member()

        login("  Member@Example.COM ", "correct-horse").andExpect { status { isOk() } }
    }

    @Test
    fun `비밀번호가 틀리거나 없는 계정이면 401`() {
        member()
        member("unclaimed@example.com", password = null)

        login("member@example.com", "wrong-password").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
        login("unclaimed@example.com", "anything-here").andExpect { status { isUnauthorized() } }
        login("nobody@example.com", "anything-here").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `5번 연속 틀리면 15분 동안 잠기고 잠금이 지나면 다시 로그인된다`() {
        val user = member()
        repeat(5) { login("member@example.com", "wrong-password").andExpect { status { isUnauthorized() } } }

        login("member@example.com", "correct-horse").andExpect {
            status { isLocked() }
            jsonPath("$.code") { value("ACCOUNT_LOCKED") }
        }

        user.lockedUntil = Instant.now().minusSeconds(1)
        users.saveAndFlush(user)
        login("member@example.com", "correct-horse").andExpect { status { isOk() } }
    }

    @Test
    fun `로그인하지 않으면 401 이고 XSRF-TOKEN 쿠키를 내려준다`() {
        mockMvc.get("/api/me").andExpect {
            status { isUnauthorized() }
            cookie { exists("XSRF-TOKEN") }
        }
    }

    @Test
    fun `CSRF 토큰 없는 변경 요청은 403`() {
        member()

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"member@example.com","password":"correct-horse"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `일반 사용자가 관리자 API 를 부르면 403`() {
        val user = member()

        mockMvc.get("/api/admin/runs") { with(authAs(requireNotNull(user.id))) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `로그아웃하면 세션이 끝난다`() {
        member()
        val session = MockHttpSession()
        login("member@example.com", "correct-horse", session).andExpect { status { isOk() } }

        mockMvc.post("/api/auth/logout") {
            this.session = session
            with(csrf())
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/me") { this.session = session }.andExpect { status { isUnauthorized() } }
    }
}
```

`api/src/test/kotlin/com/narareminder/auth/AdminBootstrapTest.kt`:

```kotlin
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
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :api:test --no-daemon`
Expected: 컴파일 실패 (`AppPrincipal`, `AccountService`, `AdminBootstrap` 없음)

- [ ] **Step 5: 구현**

`api/src/main/kotlin/com/narareminder/config/AppProperties.kt`:

```kotlin
package com.narareminder.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
data class AppProperties(
    val adminEmail: String = "",
    // 인증 메일 링크의 기준 주소
    val baseUrl: String = "http://localhost:5173",
)
```

`api/src/main/kotlin/com/narareminder/security/AppPrincipal.kt`:

```kotlin
package com.narareminder.security

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.io.Serializable

// 세션에 저장되므로 Serializable 이어야 한다
data class AppPrincipal(val userId: Long, val email: String, val admin: Boolean) : Serializable {

    fun authorities(): List<GrantedAuthority> =
        listOfNotNull(SimpleGrantedAuthority("ROLE_USER"), SimpleGrantedAuthority("ROLE_ADMIN").takeIf { admin })

    fun toAuthentication(): Authentication = UsernamePasswordAuthenticationToken.authenticated(this, null, authorities())
}
```

`api/src/main/kotlin/com/narareminder/security/CsrfCookieFilter.kt`:

```kotlin
package com.narareminder.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter

// 지연 로딩되는 CSRF 토큰을 매 요청 꺼내 XSRF-TOKEN 쿠키가 응답에 실리게 한다 (SPA 가 첫 요청에서 받아 감)
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        filterChain.doFilter(request, response)
    }
}
```

`api/src/main/kotlin/com/narareminder/security/SecurityConfig.kt`:

```kotlin
package com.narareminder.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun filterChain(http: HttpSecurity, contextRepository: SecurityContextRepository): SecurityFilterChain {
        http {
            csrf {
                // SPA 가 쿠키를 읽어 X-XSRF-TOKEN 헤더로 돌려보낸다
                csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
                csrfTokenRequestHandler = CsrfTokenRequestAttributeHandler()
            }
            securityContext {
                securityContextRepository = contextRepository
            }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
            logout {
                logoutUrl = "/api/auth/logout"
                logoutSuccessHandler = HttpStatusReturningLogoutSuccessHandler()
            }
            formLogin { disable() }
            httpBasic { disable() }
            addFilterAfter<BasicAuthenticationFilter>(CsrfCookieFilter())
        }
        return http.build()
    }
}
```

`api/src/main/kotlin/com/narareminder/auth/Credentials.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.web.BadRequestException

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    if (email.length > 254 || !EMAIL.matches(email)) throw BadRequestException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다")
    return email
}

// bcrypt 는 72바이트까지만 쓰므로 그보다 긴 비밀번호는 받지 않는다
fun validatePassword(password: String) {
    if (password.length !in 8..72) throw BadRequestException("INVALID_PASSWORD", "비밀번호는 8~72자여야 합니다")
}
```

`api/src/main/kotlin/com/narareminder/auth/AuthExceptions.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.web.ApiException
import org.springframework.http.HttpStatus

class InvalidCredentialsException :
    ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다")

class AccountLockedException :
    ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "로그인을 5번 실패해 15분 동안 잠겼습니다. 잠시 후 다시 시도하거나 비밀번호를 재설정하세요")

class InvalidInviteException :
    ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITE", "초대 코드가 올바르지 않거나 만료되었습니다")

class AlreadyRegisteredException :
    ApiException(HttpStatus.CONFLICT, "ALREADY_REGISTERED", "이미 가입된 이메일입니다")

class InvalidTokenException :
    ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "링크가 만료되었거나 이미 사용되었습니다")
```

`api/src/main/kotlin/com/narareminder/auth/AuthDtos.kt`:

```kotlin
package com.narareminder.auth

data class LoginRequest(val email: String, val password: String)

data class MeResponse(val email: String, val admin: Boolean)
```

`api/src/main/kotlin/com/narareminder/auth/AccountService.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AccountService(
    private val userRepository: AppUserRepository,
    private val userFilterRepository: UserFilterRepository,
    private val clock: Clock,
) {
    // 새 계정은 공통 조건 기본값(제외 없음·전체 업무구분·가격 제한 없음)과 함께 만든다
    @Transactional
    fun create(email: String, passwordHash: String?, admin: Boolean): AppUser {
        val user = userRepository.save(AppUser(email, passwordHash, admin, clock.instant()))
        userFilterRepository.save(UserFilter(requireNotNull(user.id)))
        return user
    }
}
```

`api/src/main/kotlin/com/narareminder/auth/AuthService.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.security.AppPrincipal
import com.narareminder.user.AppUserRepository
import com.narareminder.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) {
    // 실패 횟수 기록이 롤백되지 않도록 인증 실패 예외는 커밋한다
    @Transactional(noRollbackFor = [InvalidCredentialsException::class, AccountLockedException::class])
    fun authenticate(rawEmail: String, password: String): AppPrincipal {
        val user = userRepository.findByEmail(rawEmail.trim().lowercase()) ?: throw InvalidCredentialsException()
        val now = clock.instant()
        if (user.isLocked(now)) throw AccountLockedException()
        val hash = user.passwordHash
        if (hash == null || user.status != UserStatus.ACTIVE || !passwordEncoder.matches(password, hash)) {
            user.recordLoginFailure(now, MAX_FAILURES, LOCK_DURATION)
            throw InvalidCredentialsException()
        }
        user.recordLoginSuccess()
        return AppPrincipal(requireNotNull(user.id), user.email, user.isAdmin)
    }

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCK_DURATION: Duration = Duration.ofMinutes(15)
    }
}
```

`api/src/main/kotlin/com/narareminder/auth/AdminBootstrap.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.config.AppProperties
import com.narareminder.user.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// 첫 관리자는 ADMIN_EMAIL 로 비밀번호 없이 만들고, 비밀번호 재설정으로 첫 비밀번호를 정한다
@Component
class AdminBootstrap(
    private val props: AppProperties,
    private val userRepository: AppUserRepository,
    private val accountService: AccountService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val email = props.adminEmail.trim().lowercase()
        if (email.isEmpty()) {
            log.warn("ADMIN_EMAIL 이 비어 있어 관리자 계정을 확인하지 않습니다")
            return
        }
        val user = userRepository.findByEmail(email) ?: accountService.create(email, null, admin = true)
        user.isAdmin = true
        userRepository.save(user)
    }
}
```

`api/src/main/kotlin/com/narareminder/auth/AuthController.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.security.AppPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class AuthController(
    private val authService: AuthService,
    private val securityContextRepository: SecurityContextRepository,
) {
    @PostMapping("/auth/login")
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): MeResponse {
        val principal = authService.authenticate(body.email, body.password)
        // 세션 고정 공격을 막기 위해 로그인 때 세션 ID 를 바꾼다
        request.getSession(true)
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = principal.toAuthentication() }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        return MeResponse(principal.email, principal.admin)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AppPrincipal): MeResponse = MeResponse(principal.email, principal.admin)
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --no-daemon`
Expected: PASS (core 의 필터 테스트는 삭제되었고, api 는 `AuthApiTest`, `AdminBootstrapTest`, `JobRunApiTest`, `ApiApplicationTest` 통과)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(api): 이메일·비밀번호 로그인과 관리자 권한 추가

팀원별로 자기 알림만 다루게 하고 실행 이력은 관리자만 보게 하기 위해 세션 로그인·CSRF·잠금을 도입

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 9: 초대 코드 가입·이메일 확인·비밀번호 재설정 (api)

**Files:**
- Create: `api/src/main/kotlin/com/narareminder/auth/{TokenCodec,AuthMails,SignupService,PasswordResetService}.kt`
- Modify: `api/src/main/kotlin/com/narareminder/auth/AuthDtos.kt`, `api/src/main/kotlin/com/narareminder/auth/AuthController.kt`
- Test: `api/src/test/kotlin/com/narareminder/auth/SignupApiTest.kt`, `api/src/test/kotlin/com/narareminder/auth/PasswordResetApiTest.kt`

**Interfaces:**
- Consumes: `normalizeEmail`, `validatePassword`, `AccountService.create`, `InviteCodeRepository.findByCode/findForUpdate`, `EmailTokenRepository.findByTokenHash`, `OutboxMailRepository`, `AppProperties.baseUrl`
- Produces:
  - `TokenCodec.newToken(): String`(32바이트 URL-safe Base64), `TokenCodec.hash(token): String`(SHA-256 hex)
  - `normalizeInviteCode(raw: String): String` (trim + 대문자, `SignupService.kt` 최상위 함수)
  - `POST /api/auth/signup {inviteCode, email, password}` → 202, `POST /api/auth/verify {token}` → 204, `POST /api/auth/forgot {email}` → 202(계정 유무와 무관), `POST /api/auth/reset {token, password}` → 204
  - 메일 링크: `{baseUrl}/verify?token=…`, `{baseUrl}/reset?token=…`

- [ ] **Step 1: 실패하는 테스트 작성**

`api/src/test/kotlin/com/narareminder/auth/SignupApiTest.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.InviteCode
import com.narareminder.user.InviteCodeRepository
import com.narareminder.watch.UserFilterRepository
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class SignupApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val filters: UserFilterRepository,
    private val items: WatchItemRepository,
    private val invites: InviteCodeRepository,
    private val outbox: OutboxMailRepository,
    private val jdbc: JdbcTemplate,
    private val em: EntityManager,
) {
    private val now = Instant.now()
    private val adminId by lazy { requireNotNull(accountService.create("admin@example.com", "hash", admin = true).id) }

    private fun invite(code: String = "ABCD-2345", maxUses: Int = 5, expiresAt: Instant = now.plus(Duration.ofDays(7)), usedCount: Int = 0) =
        invites.save(InviteCode(code, maxUses, expiresAt, adminId, now, usedCount))

    private fun post(path: String, json: String): ResultActionsDsl = mockMvc.post(path) {
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun signup(code: String, email: String, password: String = "correct-horse") =
        post("/api/auth/signup", """{"inviteCode":"$code","email":"$email","password":"$password"}""")

    private fun verify(token: String) = post("/api/auth/verify", """{"token":"$token"}""")

    private fun lastTokenFor(email: String): String {
        val html = outbox.findAll().filter { it.toAddress == email }.maxBy { requireNotNull(it.id) }.html
        return requireNotNull(Regex("token=([A-Za-z0-9_-]+)").find(html)).groupValues[1]
    }

    @Test
    fun `초대 코드로 가입 요청하면 확인 메일을 넣고 아직 계정은 만들지 않는다`() {
        invite()

        signup(" abcd-2345 ", "new@example.com").andExpect { status { isAccepted() } }

        assertThat(users.findByEmail("new@example.com")).isNull()
        val mail = outbox.findAll().maxBy { requireNotNull(it.id) }
        assertThat(mail.toAddress).isEqualTo("new@example.com")
        assertThat(mail.html).contains("http://localhost:5173/verify?token=")
    }

    @Test
    fun `확인 링크를 누르면 계정과 기본 공통 조건이 생기고 소문자 이메일로 로그인된다`() {
        val code = invite()
        signup("ABCD-2345", "New@Example.com").andExpect { status { isAccepted() } }

        verify(lastTokenFor("new@example.com")).andExpect { status { isNoContent() } }

        val user = requireNotNull(users.findByEmail("new@example.com"))
        assertThat(filters.findById(requireNotNull(user.id))).isPresent()
        assertThat(invites.findById(requireNotNull(code.id)).orElseThrow().usedCount).isEqualTo(1)
        post("/api/auth/login", """{"email":"new@example.com","password":"correct-horse"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `만료·회수·한도 소진·없는 초대 코드는 400`() {
        invite("EXPI-RED2", expiresAt = now.minusSeconds(1))
        invite("FULL-USE2", maxUses = 1, usedCount = 1)
        invites.save(InviteCode("REVO-KED2", 5, now.plus(Duration.ofDays(1)), adminId, now, revokedAt = now))

        listOf("EXPI-RED2", "FULL-USE2", "REVO-KED2", "NONE-XXXX").forEach { code ->
            signup(code, "new@example.com").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_INVITE") }
            }
        }
    }

    @Test
    fun `비밀번호가 8자 미만이거나 이메일 형식이 틀리면 400`() {
        invite()

        signup("ABCD-2345", "new@example.com", password = "short").andExpect { jsonPath("$.code") { value("INVALID_PASSWORD") } }
        signup("ABCD-2345", "not-an-email").andExpect { jsonPath("$.code") { value("INVALID_EMAIL") } }
    }

    @Test
    fun `미리 만든 계정은 같은 이메일로 가입해 기존 항목과 함께 이어받는다`() {
        invite()
        val legacy = accountService.create("legacy@example.com", null, admin = false)
        items.save(WatchItem(requireNotNull(legacy.id), WatchItemType.KEYWORD, "타당성", "0 */5 * * * *", now))
        signup("ABCD-2345", "legacy@example.com").andExpect { status { isAccepted() } }

        verify(lastTokenFor("legacy@example.com")).andExpect { status { isNoContent() } }

        val claimed = requireNotNull(users.findByEmail("legacy@example.com"))
        assertThat(claimed.id).isEqualTo(legacy.id)
        assertThat(claimed.isClaimed).isTrue()
        assertThat(items.findAllByUserIdOrderByIdAsc(requireNotNull(claimed.id)).map { it.value }).containsExactly("타당성")
    }

    @Test
    fun `이미 가입된 이메일은 가입 요청과 확인에서 모두 409`() {
        invite()
        accountService.create("member@example.com", "hash", admin = false)
        signup("ABCD-2345", "member@example.com").andExpect { status { isConflict() } }

        signup("ABCD-2345", "twice@example.com")
        val first = lastTokenFor("twice@example.com")
        signup("ABCD-2345", "twice@example.com")
        val second = lastTokenFor("twice@example.com")
        verify(first).andExpect { status { isNoContent() } }
        verify(second).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ALREADY_REGISTERED") }
        }
    }

    @Test
    fun `확인 링크는 한 번만 쓸 수 있고 24시간이 지나면 400`() {
        invite()
        signup("ABCD-2345", "new@example.com")
        val token = lastTokenFor("new@example.com")
        verify(token).andExpect { status { isNoContent() } }
        verify(token).andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }

        signup("ABCD-2345", "late@example.com")
        val late = lastTokenFor("late@example.com")
        em.flush()
        jdbc.update("UPDATE email_token SET expires_at = now() - interval '1 minute' WHERE email = 'late@example.com'")
        em.clear()
        verify(late).andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }
    }
}
```

`api/src/test/kotlin/com/narareminder/auth/PasswordResetApiTest.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.PostgresTestConfig
import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class PasswordResetApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val users: AppUserRepository,
    private val outbox: OutboxMailRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val em: EntityManager,
) {
    private fun post(path: String, json: String): ResultActionsDsl = mockMvc.post(path) {
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun forgot(email: String) = post("/api/auth/forgot", """{"email":"$email"}""")
    private fun reset(token: String, password: String) = post("/api/auth/reset", """{"token":"$token","password":"$password"}""")
    private fun login(email: String, password: String) = post("/api/auth/login", """{"email":"$email","password":"$password"}""")

    private fun lastTokenFor(email: String): String {
        val html = outbox.findAll().filter { it.toAddress == email }.maxBy { requireNotNull(it.id) }.html
        return requireNotNull(Regex("token=([A-Za-z0-9_-]+)").find(html)).groupValues[1]
    }

    @Test
    fun `없는 이메일이어도 같은 응답을 주고 메일은 넣지 않는다`() {
        val before = outbox.count()

        forgot("nobody@example.com").andExpect { status { isAccepted() } }

        assertThat(outbox.count()).isEqualTo(before)
    }

    @Test
    fun `재설정 링크로 비밀번호를 바꾸면 잠금도 풀리고 새 비밀번호로 로그인된다`() {
        val user = accountService.create("member@example.com", passwordEncoder.encode("old-password"), admin = false)
        user.lockedUntil = Instant.now().plusSeconds(600)
        users.saveAndFlush(user)

        forgot("Member@Example.com").andExpect { status { isAccepted() } }
        val token = lastTokenFor("member@example.com")
        assertThat(outbox.findAll().maxBy { requireNotNull(it.id) }.html).contains("http://localhost:5173/reset?token=")
        reset(token, "new-password").andExpect { status { isNoContent() } }

        login("member@example.com", "new-password").andExpect { status { isOk() } }
        login("member@example.com", "old-password").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `비밀번호 없는 관리자 계정도 재설정으로 첫 비밀번호를 정한다`() {
        accountService.create("boss@example.com", null, admin = true)

        forgot("boss@example.com")
        reset(lastTokenFor("boss@example.com"), "first-password").andExpect { status { isNoContent() } }

        login("boss@example.com", "first-password").andExpect {
            status { isOk() }
            jsonPath("$.admin") { value(true) }
        }
    }

    @Test
    fun `재설정 링크는 한 번만 쓸 수 있고 30분이 지나면 400`() {
        accountService.create("member@example.com", passwordEncoder.encode("old-password"), admin = false)
        forgot("member@example.com")
        val token = lastTokenFor("member@example.com")
        reset(token, "new-password").andExpect { status { isNoContent() } }
        reset(token, "other-password").andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }

        forgot("member@example.com")
        val late = lastTokenFor("member@example.com")
        em.flush()
        jdbc.update("UPDATE email_token SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL")
        em.clear()
        reset(late, "new-password").andExpect { jsonPath("$.code") { value("INVALID_TOKEN") } }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :api:test --tests '*SignupApiTest' --tests '*PasswordResetApiTest' --no-daemon`
Expected: FAIL (`/api/auth/signup` 등 404)

- [ ] **Step 3: 구현**

`api/src/main/kotlin/com/narareminder/auth/TokenCodec.kt`:

```kotlin
package com.narareminder.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// 링크에는 원래 토큰을, DB 에는 해시만 둔다
object TokenCodec {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
```

`api/src/main/kotlin/com/narareminder/auth/AuthMails.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.config.AppProperties
import com.narareminder.mail.OutboxMail
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AuthMails(private val props: AppProperties) {

    fun verify(to: String, token: String, now: Instant) = OutboxMail(
        to, "[나라장터 알리미] 이메일 확인",
        html("가입을 마치려면 아래 링크를 눌러 이메일을 확인하세요. 링크는 24시간 동안 유효합니다.", link("/verify", token), "이메일 확인"),
        now,
    )

    fun reset(to: String, token: String, now: Instant) = OutboxMail(
        to, "[나라장터 알리미] 비밀번호 재설정",
        html("아래 링크에서 새 비밀번호를 정하세요. 링크는 30분 동안 유효하고 한 번만 쓸 수 있습니다.", link("/reset", token), "비밀번호 재설정"),
        now,
    )

    private fun link(path: String, token: String) = "${props.baseUrl.trimEnd('/')}$path?token=$token"

    private fun html(message: String, url: String, label: String) =
        """<p>$message</p><p><a href="$url">$label</a></p><p>요청하지 않으셨다면 이 메일을 무시하세요.</p>"""
}
```

`api/src/main/kotlin/com/narareminder/auth/SignupService.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.InviteCodeRepository
import com.narareminder.user.TokenPurpose
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

fun normalizeInviteCode(raw: String): String = raw.trim().uppercase()

@Service
class SignupService(
    private val inviteCodeRepository: InviteCodeRepository,
    private val userRepository: AppUserRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val accountService: AccountService,
    private val passwordEncoder: PasswordEncoder,
    private val authMails: AuthMails,
    private val clock: Clock,
) {
    // 이메일을 확인하기 전에는 계정을 만들지 않고, 확인 토큰에 비밀번호 해시와 초대 코드를 담아 둔다
    @Transactional
    fun requestSignup(rawCode: String, rawEmail: String, password: String) {
        val email = normalizeEmail(rawEmail)
        validatePassword(password)
        val now = clock.instant()
        val invite = inviteCodeRepository.findByCode(normalizeInviteCode(rawCode))?.takeIf { it.isUsable(now) }
            ?: throw InvalidInviteException()
        if (userRepository.findByEmail(email)?.isClaimed == true) throw AlreadyRegisteredException()

        val token = TokenCodec.newToken()
        emailTokenRepository.save(
            EmailToken(email, TokenPurpose.VERIFY, TokenCodec.hash(token), passwordEncoder.encode(password), invite.id, now.plus(VERIFY_TTL), now),
        )
        outboxMailRepository.save(authMails.verify(email, token, now))
    }

    @Transactional
    fun verify(token: String) {
        val now = clock.instant()
        val emailToken = emailTokenRepository.findByTokenHash(TokenCodec.hash(token))?.takeIf { it.isUsable(TokenPurpose.VERIFY, now) }
            ?: throw InvalidTokenException()
        val passwordHash = emailToken.passwordHash ?: throw InvalidTokenException()
        val invite = emailToken.inviteCodeId?.let { inviteCodeRepository.findForUpdate(it) }?.takeIf { it.isUsable(now) }
            ?: throw InvalidInviteException()

        val existing = userRepository.findByEmail(emailToken.email)
        when {
            existing == null -> accountService.create(emailToken.email, passwordHash, admin = false)
            !existing.isClaimed -> existing.changePassword(passwordHash)
            else -> throw AlreadyRegisteredException()
        }
        invite.use()
        emailToken.usedAt = now
    }

    companion object {
        private val VERIFY_TTL: Duration = Duration.ofHours(24)
    }
}
```

`api/src/main/kotlin/com/narareminder/auth/PasswordResetService.kt`:

```kotlin
package com.narareminder.auth

import com.narareminder.mail.OutboxMailRepository
import com.narareminder.user.AppUserRepository
import com.narareminder.user.EmailToken
import com.narareminder.user.EmailTokenRepository
import com.narareminder.user.TokenPurpose
import com.narareminder.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class PasswordResetService(
    private val userRepository: AppUserRepository,
    private val emailTokenRepository: EmailTokenRepository,
    private val outboxMailRepository: OutboxMailRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authMails: AuthMails,
    private val clock: Clock,
) {
    // 가입 여부가 드러나지 않도록 계정이 없어도 호출한 쪽에는 같은 응답을 준다
    @Transactional
    fun requestReset(rawEmail: String) {
        val email = rawEmail.trim().lowercase()
        val user = userRepository.findByEmail(email) ?: return
        if (user.status != UserStatus.ACTIVE) return
        val now = clock.instant()
        val token = TokenCodec.newToken()
        emailTokenRepository.save(EmailToken(email, TokenPurpose.RESET, TokenCodec.hash(token), null, null, now.plus(RESET_TTL), now))
        outboxMailRepository.save(authMails.reset(email, token, now))
    }

    @Transactional
    fun reset(token: String, password: String) {
        validatePassword(password)
        val now = clock.instant()
        val emailToken = emailTokenRepository.findByTokenHash(TokenCodec.hash(token))?.takeIf { it.isUsable(TokenPurpose.RESET, now) }
            ?: throw InvalidTokenException()
        val user = userRepository.findByEmail(emailToken.email) ?: throw InvalidTokenException()
        user.changePassword(passwordEncoder.encode(password))
        emailToken.usedAt = now
    }

    companion object {
        private val RESET_TTL: Duration = Duration.ofMinutes(30)
    }
}
```

`AuthDtos.kt` 에 추가:

```kotlin
data class SignupRequest(val inviteCode: String, val email: String, val password: String)

data class TokenRequest(val token: String)

data class ForgotRequest(val email: String)

data class ResetRequest(val token: String, val password: String)
```

`AuthController.kt` 생성자에 `private val signupService: SignupService,` 와 `private val passwordResetService: PasswordResetService,` 를 추가하고 메서드를 추가한다(import `org.springframework.http.HttpStatus`, `org.springframework.web.bind.annotation.ResponseStatus`):

```kotlin
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun signup(@RequestBody body: SignupRequest) {
        signupService.requestSignup(body.inviteCode, body.email, body.password)
    }

    @PostMapping("/auth/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verify(@RequestBody body: TokenRequest) {
        signupService.verify(body.token)
    }

    @PostMapping("/auth/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgot(@RequestBody body: ForgotRequest) {
        passwordResetService.requestReset(body.email)
    }

    @PostMapping("/auth/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@RequestBody body: ResetRequest) {
        passwordResetService.reset(body.token, body.password)
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :api:test --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(api): 초대 코드 가입과 이메일 확인·비밀번호 재설정 추가

팀원이 직접 가입하고 미리 만든 계정을 이어받을 수 있게 하며, 인증 메일은 outbox 로 worker 에 넘기기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 10: 내 항목·공통 조건·cron 미리보기 API (api)

**Files:**
- Create: `api/src/main/kotlin/com/narareminder/items/{ItemDtos,ItemValues,ItemQueries,ItemService,ItemController}.kt`
- Create: `api/src/main/kotlin/com/narareminder/settings/{SettingsDtos,SettingsService,SettingsController}.kt`
- Create: `api/src/main/kotlin/com/narareminder/cron/CronController.kt`
- Test: `api/src/test/kotlin/com/narareminder/items/ItemApiTest.kt`, `api/src/test/kotlin/com/narareminder/settings/SettingsApiTest.kt`, `api/src/test/kotlin/com/narareminder/cron/CronApiTest.kt`

**Interfaces:**
- Consumes: `Cadence`, `WatchItemRepository`, `UserFilterRepository`, `AppPrincipal`, `authAs`, `BadRequestException`, `ConflictException`, `NotFoundException`
- Produces (JSON 필드명 그대로 프론트가 쓴다):
  - `ItemResponse(id: Long, type: WatchItemType, value: String, cron: String, nextSendAt: Instant?, createdAt: Instant)`
  - `ItemStats(matched: Int, sent: Int, pending: Int)`
  - `RecentNotice(id: Long, status: DeliveryStatus, matchedAt: Instant, sentAt: Instant?, scheduledAt: Instant?, source: NoticeSource, noticeNo: String, title: String, agency: String?, price: Long?, deadlineAt: LocalDateTime?, detailUrl: String?, revision: Boolean)`
  - `ItemDetailResponse(item: ItemResponse, stats: ItemStats, recent: List<RecentNotice>)`
  - `CreateItemRequest(type: WatchItemType, value: String, cron: String = Cadence.IMMEDIATE)`, `UpdateItemRequest(value: String? = null, cron: String? = null)`
  - `SettingsResponse/SettingsRequest(excludeKeywords: List<String>, categories: List<NoticeCategory>, priceMin: Long?, priceMax: Long?)`
  - `CronPreviewResponse(cron: String, next: List<Instant>)`
  - `GET/POST /api/items`, `GET/PATCH/DELETE /api/items/{id}`(남의 것은 404), `GET/PUT /api/settings`, `GET /api/cron/preview?expr=`
  - `normalizeValue(raw: String): String` (1~100자, 줄바꿈 불가, 400 `INVALID_VALUE`)

- [ ] **Step 1: 실패하는 테스트 작성**

`api/src/test/kotlin/com/narareminder/items/ItemApiTest.kt`:

```kotlin
package com.narareminder.items

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.cadence.Cadence
import com.narareminder.delivery.Delivery
import com.narareminder.delivery.DeliveryItem
import com.narareminder.delivery.DeliveryItemId
import com.narareminder.delivery.DeliveryItemRepository
import com.narareminder.delivery.DeliveryRepository
import com.narareminder.notice.NoticeCategory
import com.narareminder.notice.NoticeKey
import com.narareminder.notice.NoticeSource
import com.narareminder.notice.StoredNotice
import com.narareminder.notice.StoredNoticeRepository
import com.narareminder.support.authAs
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class ItemApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val items: WatchItemRepository,
    private val notices: StoredNoticeRepository,
    private val deliveries: DeliveryRepository,
    private val deliveryItems: DeliveryItemRepository,
) {
    private val me by lazy { requireNotNull(accountService.create("me@example.com", "hash", admin = false).id) }
    private val other by lazy { requireNotNull(accountService.create("other@example.com", "hash", admin = false).id) }

    private fun create(json: String, userId: Long = me) = mockMvc.post("/api/items") {
        with(authAs(userId))
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun itemOf(userId: Long, value: String, cron: String = Cadence.IMMEDIATE): Long =
        requireNotNull(items.save(WatchItem(userId, WatchItemType.KEYWORD, value, cron, Instant.now())).id)

    @Test
    fun `항목을 추가하면 기본 주기는 즉시이고 다음 발송 시각과 함께 목록에 나온다`() {
        create("""{"type":"KEYWORD","value":"  플랫폼  "}""").andExpect {
            status { isCreated() }
            jsonPath("$.value") { value("플랫폼") }
            jsonPath("$.cron") { value("0 */5 * * * *") }
            jsonPath("$.nextSendAt") { value(notNullValue()) }
        }

        mockMvc.get("/api/items") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].type") { value("KEYWORD") }
        }
    }

    @Test
    fun `값이 비었거나 100자를 넘으면 400`() {
        create("""{"type":"KEYWORD","value":"   "}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_VALUE") }
        }
        create("""{"type":"KEYWORD","value":"${"가".repeat(101)}"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `같은 종류에서 대소문자만 다른 값은 409, 다른 종류면 허용`() {
        create("""{"type":"KEYWORD","value":"ODA"}""").andExpect { status { isCreated() } }

        create("""{"type":"KEYWORD","value":"oda"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE") }
        }
        create("""{"type":"AGENCY","value":"ODA"}""").andExpect { status { isCreated() } }
    }

    @Test
    fun `형식이 틀리거나 5분보다 촘촘한 cron 은 400`() {
        create("""{"type":"KEYWORD","value":"플랫폼","cron":"매일"}""").andExpect { jsonPath("$.code") { value("INVALID_CRON") } }
        create("""{"type":"KEYWORD","value":"플랫폼","cron":"0 * * * * *"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("발송 간격은 5분 이상이어야 합니다") }
        }
    }

    @Test
    fun `값과 주기를 고치고 지울 수 있다`() {
        val id = itemOf(me, "플랫폼")

        mockMvc.patch("/api/items/$id") {
            with(authAs(me))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"value":"클라우드","cron":"0 0 9 * * MON-FRI"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value") { value("클라우드") }
            jsonPath("$.cron") { value("0 0 9 * * MON-FRI") }
        }

        mockMvc.delete("/api/items/$id") {
            with(authAs(me))
            with(csrf())
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `남의 항목은 조회·수정·삭제 모두 404`() {
        val id = itemOf(other, "플랫폼")

        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect { status { isNotFound() } }
        mockMvc.patch("/api/items/$id") {
            with(authAs(me))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"value":"가로채기"}"""
        }.andExpect { status { isNotFound() } }
        mockMvc.delete("/api/items/$id") {
            with(authAs(me))
            with(csrf())
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `상세에는 최근 7일 통계와 최근 걸린 공고의 상태·예정 발송 시각이 나온다`() {
        val id = itemOf(me, "타당성", cron = "0 0 9 * * *")
        val matchedAt = Instant.now().minus(Duration.ofHours(1))
        fun notice(no: String, ord: String = "000") = notices.save(
            StoredNotice(
                NoticeKey(NoticeSource.BID, no, ord), "도로 타당성조사 $no", NoticeCategory.SERVICE, 240_000_000, "강원특별자치도",
                null, LocalDateTime.of(2026, 11, 5, 10, 0), "https://g2b/$no", matchedAt,
            ),
        )
        val sent = deliveries.save(Delivery(me, notice("R1").id, matchedAt.minusSeconds(60)).apply { markSent(matchedAt) })
        val pending = deliveries.save(Delivery(me, notice("R2", "001").id, matchedAt))
        deliveryItems.saveAll(listOf(sent, pending).map { DeliveryItem(DeliveryItemId(it.requireId(), id)) })

        mockMvc.get("/api/items/$id") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.item.value") { value("타당성") }
            jsonPath("$.stats.matched") { value(2) }
            jsonPath("$.stats.sent") { value(1) }
            jsonPath("$.stats.pending") { value(1) }
            jsonPath("$.recent.length()") { value(2) }
            jsonPath("$.recent[0].status") { value("PENDING") }
            jsonPath("$.recent[0].revision") { value(true) }
            jsonPath("$.recent[0].scheduledAt") { value(notNullValue()) }
            jsonPath("$.recent[0].deadlineAt") { value(startsWith("2026-11-05T10:00")) }
            jsonPath("$.recent[1].status") { value("SENT") }
            jsonPath("$.recent[1].scheduledAt") { value(nullValue()) }
            jsonPath("$.recent[1].agency") { value("강원특별자치도") }
        }
    }

    @Test
    fun `로그인하지 않으면 401`() {
        mockMvc.get("/api/items").andExpect { status { isUnauthorized() } }
    }
}
```

`api/src/test/kotlin/com/narareminder/settings/SettingsApiTest.kt`:

```kotlin
package com.narareminder.settings

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.support.authAs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class SettingsApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
) {
    private val me by lazy { requireNotNull(accountService.create("me@example.com", "hash", admin = false).id) }

    private fun save(json: String) = mockMvc.put("/api/settings") {
        with(authAs(me))
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    @Test
    fun `새 사용자의 공통 조건은 제외 없음·전체 업무구분·가격 제한 없음이다`() {
        mockMvc.get("/api/settings") { with(authAs(me)) }.andExpect {
            status { isOk() }
            jsonPath("$.excludeKeywords.length()") { value(0) }
            jsonPath("$.categories.length()") { value(4) }
            jsonPath("$.priceMin") { value(null) }
        }
    }

    @Test
    fun `저장하면 제외 키워드를 다듬고 대소문자 중복을 없앤다`() {
        save("""{"excludeKeywords":[" 교육 ","청소","교육"],"categories":["SERVICE"],"priceMin":1000,"priceMax":null}""").andExpect {
            status { isOk() }
            jsonPath("$.excludeKeywords.length()") { value(2) }
            jsonPath("$.excludeKeywords[0]") { value("교육") }
            jsonPath("$.categories[0]") { value("SERVICE") }
            jsonPath("$.priceMin") { value(1000) }
        }
        mockMvc.get("/api/settings") { with(authAs(me)) }.andExpect { jsonPath("$.excludeKeywords[1]") { value("청소") } }
    }

    @Test
    fun `업무구분이 없거나 가격 범위가 뒤집히면 400`() {
        save("""{"excludeKeywords":[],"categories":[],"priceMin":null,"priceMax":null}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_SETTINGS") }
        }
        save("""{"excludeKeywords":[],"categories":["GOODS"],"priceMin":2000,"priceMax":1000}""").andExpect { status { isBadRequest() } }
        save("""{"excludeKeywords":[],"categories":["GOODS"],"priceMin":-1,"priceMax":null}""").andExpect { status { isBadRequest() } }
    }
}
```

`api/src/test/kotlin/com/narareminder/cron/CronApiTest.kt`:

```kotlin
package com.narareminder.cron

import com.narareminder.PostgresTestConfig
import com.narareminder.support.authAs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
class CronApiTest @Autowired constructor(private val mockMvc: MockMvc) {

    @Test
    fun `유효한 cron 은 다음 발송 3회를 돌려준다`() {
        mockMvc.get("/api/cron/preview") {
            with(authAs(1))
            param("expr", "0 */30 9-17 * * MON-FRI")
        }.andExpect {
            status { isOk() }
            jsonPath("$.cron") { value("0 */30 9-17 * * MON-FRI") }
            jsonPath("$.next.length()") { value(3) }
        }
    }

    @Test
    fun `틀리거나 촘촘한 cron 은 400 INVALID_CRON`() {
        mockMvc.get("/api/cron/preview") {
            with(authAs(1))
            param("expr", "0 * * * * *")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CRON") }
        }
    }

    @Test
    fun `로그인하지 않으면 401`() {
        mockMvc.get("/api/cron/preview") { param("expr", "0 */5 * * * *") }.andExpect { status { isUnauthorized() } }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :api:test --tests '*ItemApiTest' --tests '*SettingsApiTest' --tests '*CronApiTest' --no-daemon`
Expected: FAIL (404)

- [ ] **Step 3: 구현**

`api/src/main/kotlin/com/narareminder/items/ItemValues.kt`:

```kotlin
package com.narareminder.items

import com.narareminder.web.BadRequestException

// 제외 키워드는 줄바꿈으로 구분해 저장하므로 값에 줄바꿈을 허용하지 않는다
fun normalizeValue(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty() || value.length > MAX_VALUE_LENGTH || value.contains('\n')) {
        throw BadRequestException("INVALID_VALUE", "값은 1~${MAX_VALUE_LENGTH}자여야 합니다")
    }
    return value
}

private const val MAX_VALUE_LENGTH = 100
```

`api/src/main/kotlin/com/narareminder/items/ItemDtos.kt`:

```kotlin
package com.narareminder.items

import com.narareminder.cadence.Cadence
import com.narareminder.delivery.DeliveryStatus
import com.narareminder.notice.NoticeSource
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemType
import java.time.Instant
import java.time.LocalDateTime

data class ItemResponse(
    val id: Long, val type: WatchItemType, val value: String, val cron: String, val nextSendAt: Instant?, val createdAt: Instant,
)

data class ItemStats(val matched: Int, val sent: Int, val pending: Int)

data class RecentNotice(
    val id: Long, val status: DeliveryStatus, val matchedAt: Instant, val sentAt: Instant?, val scheduledAt: Instant?,
    val source: NoticeSource, val noticeNo: String, val title: String, val agency: String?, val price: Long?,
    val deadlineAt: LocalDateTime?, val detailUrl: String?, val revision: Boolean,
)

data class ItemDetailResponse(val item: ItemResponse, val stats: ItemStats, val recent: List<RecentNotice>)

data class CreateItemRequest(val type: WatchItemType, val value: String, val cron: String = Cadence.IMMEDIATE)

data class UpdateItemRequest(val value: String? = null, val cron: String? = null)

fun WatchItem.toResponse(now: Instant) = ItemResponse(
    id = requireNotNull(id), type = type, value = value, cron = cron, nextSendAt = Cadence.nextFire(cron, now), createdAt = createdAt,
)
```

`api/src/main/kotlin/com/narareminder/items/ItemQueries.kt`:

```kotlin
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
```

`api/src/main/kotlin/com/narareminder/items/ItemService.kt`:

```kotlin
package com.narareminder.items

import com.narareminder.cadence.Cadence
import com.narareminder.watch.WatchItem
import com.narareminder.watch.WatchItemRepository
import com.narareminder.watch.WatchItemType
import com.narareminder.web.ConflictException
import com.narareminder.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class ItemService(
    private val itemRepository: WatchItemRepository,
    private val queries: ItemQueries,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<ItemResponse> {
        val now = clock.instant()
        return itemRepository.findAllByUserIdOrderByIdAsc(userId).map { it.toResponse(now) }
    }

    @Transactional
    fun create(userId: Long, request: CreateItemRequest): ItemResponse {
        val now = clock.instant()
        val value = normalizeValue(request.value)
        val cron = Cadence.validate(request.cron, now)
        if (itemRepository.existsByUserIdAndTypeAndValueIgnoreCase(userId, request.type, value)) throw duplicate(request.type, value)
        return itemRepository.save(WatchItem(userId, request.type, value, cron, now)).toResponse(now)
    }

    @Transactional(readOnly = true)
    fun detail(userId: Long, id: Long): ItemDetailResponse {
        val now = clock.instant()
        val item = find(userId, id)
        return ItemDetailResponse(item.toResponse(now), queries.stats(id, now.minus(STATS_PERIOD)), queries.recent(id))
    }

    @Transactional
    fun update(userId: Long, id: Long, request: UpdateItemRequest): ItemResponse {
        val now = clock.instant()
        val item = find(userId, id)
        request.value?.let { raw ->
            val value = normalizeValue(raw)
            if (itemRepository.existsByUserIdAndTypeAndValueIgnoreCaseAndIdNot(userId, item.type, value, id)) throw duplicate(item.type, value)
            item.value = value
        }
        request.cron?.let { item.cron = Cadence.validate(it, now) }
        return item.toResponse(now)
    }

    @Transactional
    fun delete(userId: Long, id: Long) {
        itemRepository.delete(find(userId, id))
    }

    // 남의 항목도 없는 항목과 똑같이 404 로 응답한다
    private fun find(userId: Long, id: Long): WatchItem =
        itemRepository.findByIdAndUserId(id, userId) ?: throw NotFoundException("항목이 없습니다: $id")

    private fun duplicate(type: WatchItemType, value: String) = ConflictException("DUPLICATE", "이미 등록된 ${type.label}입니다: $value")

    companion object {
        private val STATS_PERIOD: Duration = Duration.ofDays(7)
    }
}
```

`api/src/main/kotlin/com/narareminder/items/ItemController.kt`:

```kotlin
package com.narareminder.items

import com.narareminder.security.AppPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/items")
class ItemController(private val itemService: ItemService) {

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AppPrincipal): List<ItemResponse> = itemService.list(principal.userId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: CreateItemRequest): ItemResponse =
        itemService.create(principal.userId, body)

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long): ItemDetailResponse =
        itemService.detail(principal.userId, id)

    @PatchMapping("/{id}")
    fun update(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long, @RequestBody body: UpdateItemRequest): ItemResponse =
        itemService.update(principal.userId, id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long) {
        itemService.delete(principal.userId, id)
    }
}
```

`api/src/main/kotlin/com/narareminder/settings/SettingsDtos.kt`:

```kotlin
package com.narareminder.settings

import com.narareminder.notice.NoticeCategory
import com.narareminder.watch.UserFilter

data class SettingsRequest(
    val excludeKeywords: List<String>, val categories: List<NoticeCategory>, val priceMin: Long?, val priceMax: Long?,
)

data class SettingsResponse(
    val excludeKeywords: List<String>, val categories: List<NoticeCategory>, val priceMin: Long?, val priceMax: Long?,
)

fun UserFilter.toResponse() = SettingsResponse(excludeKeywordList(), categoryList(), priceMin, priceMax)
```

`api/src/main/kotlin/com/narareminder/settings/SettingsService.kt`:

```kotlin
package com.narareminder.settings

import com.narareminder.items.normalizeValue
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import com.narareminder.web.BadRequestException
import com.narareminder.web.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SettingsService(private val repository: UserFilterRepository) {

    @Transactional(readOnly = true)
    fun get(userId: Long): SettingsResponse = load(userId).toResponse()

    @Transactional
    fun update(userId: Long, request: SettingsRequest): SettingsResponse {
        val (min, max) = request.priceMin to request.priceMax
        if (request.categories.isEmpty()) throw invalid("업무구분을 하나 이상 선택하세요")
        if ((min ?: 0) < 0 || (max ?: 0) < 0) throw invalid("가격은 0 이상이어야 합니다")
        if (min != null && max != null && min > max) throw invalid("최소 가격이 최대 가격보다 클 수 없습니다")
        val keywords = request.excludeKeywords.map { normalizeValue(it) }.distinctBy { it.lowercase() }
        return load(userId).apply { update(keywords, request.categories.distinct(), min, max) }.toResponse()
    }

    private fun load(userId: Long): UserFilter =
        repository.findByIdOrNull(userId) ?: throw NotFoundException("공통 조건이 없습니다: $userId")

    private fun invalid(message: String) = BadRequestException("INVALID_SETTINGS", message)
}
```

`api/src/main/kotlin/com/narareminder/settings/SettingsController.kt`:

```kotlin
package com.narareminder.settings

import com.narareminder.security.AppPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/settings")
class SettingsController(private val settingsService: SettingsService) {

    @GetMapping
    fun get(@AuthenticationPrincipal principal: AppPrincipal): SettingsResponse = settingsService.get(principal.userId)

    @PutMapping
    fun update(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: SettingsRequest): SettingsResponse =
        settingsService.update(principal.userId, body)
}
```

`api/src/main/kotlin/com/narareminder/cron/CronController.kt`:

```kotlin
package com.narareminder.cron

import com.narareminder.cadence.Cadence
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

data class CronPreviewResponse(val cron: String, val next: List<Instant>)

// 화면의 주기 편집기와 worker 가 같은 계산(Spring CronExpression)을 쓰도록 미리보기를 서버에서 만든다
@RestController
class CronController(private val clock: Clock) {

    @GetMapping("/api/cron/preview")
    fun preview(@RequestParam expr: String): CronPreviewResponse {
        val now = clock.instant()
        val cron = Cadence.validate(expr, now)
        return CronPreviewResponse(cron, Cadence.nextTimes(cron, now, PREVIEW_COUNT))
    }

    companion object {
        private const val PREVIEW_COUNT = 3
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :api:test --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(api): 내 키워드·수요기관과 공통 조건, 주기 미리보기 API 추가

사용자마다 항목별 주기를 정하고 상세 화면에서 최근 걸린 공고와 발송 상태를 보게 하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 11: 초대 코드 관리 API (api)

**Files:**
- Create: `api/src/main/kotlin/com/narareminder/admin/{InviteService,InviteController}.kt`
- Test: `api/src/test/kotlin/com/narareminder/admin/InviteApiTest.kt`

**Interfaces:**
- Consumes: `InviteCode`, `InviteCodeRepository`, `InviteStatus`, `AppPrincipal`
- Produces:
  - `InviteResponse(id: Long, code: String, status: InviteStatus, maxUses: Int, usedCount: Int, expiresAt: Instant, createdAt: Instant)`
  - `CreateInviteRequest(maxUses: Int, validDays: Int)` — 사용 한도 1~100, 유효 기간 1~30일, 아니면 400 `INVALID_INVITE_OPTION`
  - `GET /api/admin/invites`(최근 생성 순), `POST /api/admin/invites` → 201, `POST /api/admin/invites/{id}/revoke`
  - 코드 형식 `XXXX-XXXX`(헷갈리는 0/O/1/I 제외한 대문자·숫자)

- [ ] **Step 1: 실패하는 테스트 작성**

`api/src/test/kotlin/com/narareminder/admin/InviteApiTest.kt`:

```kotlin
package com.narareminder.admin

import com.narareminder.PostgresTestConfig
import com.narareminder.auth.AccountService
import com.narareminder.support.authAs
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfig::class)
@Transactional
class InviteApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
) {
    private val adminId by lazy { requireNotNull(accountService.create("admin@example.com", "hash", admin = true).id) }

    private fun create(json: String, admin: Boolean = true) = mockMvc.post("/api/admin/invites") {
        with(authAs(adminId, "admin@example.com", admin))
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    @Test
    fun `초대 코드를 만들면 사용 가능 상태로 목록 맨 위에 나온다`() {
        create("""{"maxUses":5,"validDays":7}""").andExpect {
            status { isCreated() }
            jsonPath("$.code") { value(matchesPattern("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$")) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.maxUses") { value(5) }
            jsonPath("$.usedCount") { value(0) }
        }
        create("""{"maxUses":1,"validDays":1}""")

        mockMvc.get("/api/admin/invites") { with(authAs(adminId, admin = true)) }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].maxUses") { value(1) }
        }
    }

    @Test
    fun `회수하면 REVOKED 가 된다`() {
        val body = create("""{"maxUses":5,"validDays":7}""").andReturn().response.contentAsString
        val id = requireNotNull(Regex("\"id\":(\\d+)").find(body)).groupValues[1]

        mockMvc.post("/api/admin/invites/$id/revoke") {
            with(authAs(adminId, admin = true))
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REVOKED") }
        }
    }

    @Test
    fun `사용 한도·유효 기간이 범위를 벗어나면 400`() {
        create("""{"maxUses":0,"validDays":7}""").andExpect { jsonPath("$.code") { value("INVALID_INVITE_OPTION") } }
        create("""{"maxUses":5,"validDays":31}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `관리자가 아니면 403`() {
        create("""{"maxUses":5,"validDays":7}""", admin = false).andExpect { status { isForbidden() } }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :api:test --tests '*InviteApiTest' --no-daemon`
Expected: FAIL (404)

- [ ] **Step 3: 구현**

`api/src/main/kotlin/com/narareminder/admin/InviteService.kt`:

```kotlin
package com.narareminder.admin

import com.narareminder.user.InviteCode
import com.narareminder.user.InviteCodeRepository
import com.narareminder.user.InviteStatus
import com.narareminder.web.BadRequestException
import com.narareminder.web.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class InviteResponse(
    val id: Long, val code: String, val status: InviteStatus, val maxUses: Int, val usedCount: Int,
    val expiresAt: Instant, val createdAt: Instant,
)

data class CreateInviteRequest(val maxUses: Int, val validDays: Int)

@Service
class InviteService(
    private val repository: InviteCodeRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun list(): List<InviteResponse> {
        val now = clock.instant()
        return repository.findAllByOrderByIdDesc().map { it.toResponse(now) }
    }

    @Transactional
    fun create(adminId: Long, request: CreateInviteRequest): InviteResponse {
        if (request.maxUses !in 1..MAX_USES) throw BadRequestException("INVALID_INVITE_OPTION", "사용 한도는 1~${MAX_USES}명이어야 합니다")
        if (request.validDays !in 1..MAX_DAYS) throw BadRequestException("INVALID_INVITE_OPTION", "유효 기간은 1~${MAX_DAYS}일이어야 합니다")
        val now = clock.instant()
        val invite = InviteCode(newCode(), request.maxUses, now.plus(Duration.ofDays(request.validDays.toLong())), adminId, now)
        return repository.save(invite).toResponse(now)
    }

    @Transactional
    fun revoke(id: Long): InviteResponse {
        val now = clock.instant()
        val invite = repository.findByIdOrNull(id) ?: throw NotFoundException("초대 코드가 없습니다: $id")
        if (invite.revokedAt == null) invite.revoke(now)
        return invite.toResponse(now)
    }

    // 손으로 옮겨 적기 쉽도록 헷갈리는 글자(0, O, 1, I)를 뺐다
    private fun newCode(): String {
        val chars = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${chars.take(4)}-${chars.drop(4)}"
    }

    private fun InviteCode.toResponse(now: Instant) =
        InviteResponse(requireNotNull(id), code, status(now), maxUses, usedCount, expiresAt, createdAt)

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val MAX_USES = 100
        private const val MAX_DAYS = 30
    }
}
```

`api/src/main/kotlin/com/narareminder/admin/InviteController.kt`:

```kotlin
package com.narareminder.admin

import com.narareminder.security.AppPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/invites")
class InviteController(private val inviteService: InviteService) {

    @GetMapping
    fun list(): List<InviteResponse> = inviteService.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: CreateInviteRequest): InviteResponse =
        inviteService.create(principal.userId, body)

    @PostMapping("/{id}/revoke")
    fun revoke(@PathVariable id: Long): InviteResponse = inviteService.revoke(id)
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --no-daemon`
Expected: PASS (전체)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(api): 관리자용 초대 코드 생성·조회·회수 API 추가

관리자가 사용 한도와 유효 기간이 있는 초대 코드로 팀원 가입을 통제하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 12: 화면 — 로그인·가입·확인·비밀번호 재설정 (admin)

**Files:**
- Modify: `admin/src/api/client.ts`, `admin/src/types.ts`, `admin/src/App.tsx`, `admin/src/styles.css`
- Create: `admin/src/api/auth.ts`, `admin/src/auth/AuthContext.tsx`, `admin/src/components/Header.tsx`
- Create: `admin/src/pages/{LoginPage,SignupPage,VerifyPage,ForgotPage,ResetPage}.tsx`
- Test: `admin/src/api/client.test.ts`, `admin/src/test/renderRoutes.tsx`, `admin/src/auth/AuthContext.test.tsx`, `admin/src/pages/{LoginPage,SignupPage,VerifyPage,ResetPage}.test.tsx`

**Interfaces:**
- Consumes: Task 8·9 API (`/api/me`, `/api/auth/login|logout|signup|verify|forgot|reset`)
- Produces:
  - `request<T>(path, init?)` — GET 이 아닌 요청에 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 붙인다. 빈 본문이면 `undefined`
  - `types.ts`: `interface Me { email: string; admin: boolean }`
  - `api/auth.ts`: `fetchMe`, `login(email, password)`, `logout`, `signup(input: SignupInput)`, `verifyEmail(token)`, `requestPasswordReset(email)`, `resetPassword(token, password)`
  - `auth/AuthContext.tsx`: `AuthProvider`, `useAuth(): { me: Me | null; loading: boolean; setMe }`, `RequireAuth({ admin?, children })`
  - `components/Header.tsx`: `Header`
  - 테스트 도우미 `test/renderRoutes.tsx`: `renderRoutes(routes: RouteSpec[], initialPath: string)`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/src/test/renderRoutes.tsx`:

```tsx
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '../auth/AuthContext'

export interface RouteSpec {
  path: string
  element: ReactElement
}

export function renderRoutes(routes: RouteSpec[], initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <Routes>
          {routes.map((route) => (
            <Route key={route.path} path={route.path} element={route.element} />
          ))}
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}
```

`admin/src/api/client.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from './client'

const respond = (status: number, body: string) => vi.fn().mockResolvedValue(new Response(body || null, { status }))

describe('request', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token%2Babc'
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('변경 요청에는 XSRF-TOKEN 쿠키 값을 헤더로 붙인다', async () => {
    const fetchMock = respond(200, '{"ok":true}')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/items', { method: 'POST', body: '{}' })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'token+abc' })
  })

  it('GET 에는 CSRF 헤더를 붙이지 않는다', async () => {
    const fetchMock = respond(200, '[]')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/items')

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('본문이 없으면 undefined, 오류면 ApiError 를 던진다', async () => {
    vi.stubGlobal('fetch', respond(202, ''))
    await expect(request('/api/auth/signup', { method: 'POST' })).resolves.toBeUndefined()

    vi.stubGlobal('fetch', respond(409, '{"code":"DUPLICATE","message":"이미 등록된 키워드입니다"}'))
    await expect(request('/api/items', { method: 'POST' })).rejects.toMatchObject({ status: 409, code: 'DUPLICATE', message: '이미 등록된 키워드입니다' })
  })
})
```

`admin/src/auth/AuthContext.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe } from '../api/auth'
import { Header } from '../components/Header'
import { renderRoutes } from '../test/renderRoutes'
import { RequireAuth } from './AuthContext'

vi.mock('../api/auth')

const routes = [
  { path: '/login', element: <p>로그인 화면</p> },
  { path: '/items', element: <><Header /><RequireAuth><p>내 알림 화면</p></RequireAuth></> },
  { path: '/admin/runs', element: <RequireAuth admin><p>실행 이력 화면</p></RequireAuth> },
]

describe('RequireAuth / Header', () => {
  beforeEach(() => vi.resetAllMocks())

  it('로그인하지 않았으면 로그인 화면으로 보낸다', async () => {
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
    renderRoutes(routes, '/items')

    expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
  })

  it('일반 사용자는 관리자 화면 대신 내 알림으로 가고 관리자 메뉴가 보이지 않는다', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ email: 'me@example.com', admin: false })
    renderRoutes(routes, '/admin/runs')

    expect(await screen.findByText('내 알림 화면')).toBeInTheDocument()
    expect(screen.getByText('me@example.com')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '초대 코드' })).not.toBeInTheDocument()
  })

  it('관리자에게는 관리자 메뉴가 보인다', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ email: 'admin@example.com', admin: true })
    renderRoutes(routes, '/items')

    expect(await screen.findByRole('link', { name: '초대 코드' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '실행 이력' })).toBeInTheDocument()
  })
})
```

`admin/src/pages/LoginPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, login } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { LoginPage } from './LoginPage'

vi.mock('../api/auth')

const routes = [
  { path: '/login', element: <LoginPage /> },
  { path: '/items', element: <p>내 알림 화면</p> },
]

describe('LoginPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('로그인하면 내 알림 화면으로 간다', async () => {
    vi.mocked(login).mockResolvedValue({ email: 'me@example.com', admin: false })
    renderRoutes(routes, '/login')

    await userEvent.type(screen.getByLabelText('이메일'), 'me@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(login).toHaveBeenCalledWith('me@example.com', 'correct-horse')
    expect(await screen.findByText('내 알림 화면')).toBeInTheDocument()
  })

  it('실패하면 서버 메시지를 보여 준다', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError(423, 'ACCOUNT_LOCKED', '로그인을 5번 실패해 15분 동안 잠겼습니다'))
    renderRoutes(routes, '/login')

    await userEvent.type(screen.getByLabelText('이메일'), 'me@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('15분 동안 잠겼습니다')
  })
})
```

`admin/src/pages/SignupPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, signup } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { SignupPage } from './SignupPage'

vi.mock('../api/auth')

describe('SignupPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  const fill = async (password: string, confirm: string) => {
    await userEvent.type(screen.getByLabelText('이메일'), 'new@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), password)
    await userEvent.type(screen.getByLabelText('비밀번호 확인'), confirm)
    await userEvent.click(screen.getByRole('button', { name: '가입 요청' }))
  }

  it('주소의 code 로 초대 코드를 채우고, 가입 요청하면 확인 메일 안내를 보여 준다', async () => {
    vi.mocked(signup).mockResolvedValue(undefined)
    renderRoutes([{ path: '/signup', element: <SignupPage /> }], '/signup?code=ABCD-2345')

    expect(screen.getByLabelText('초대 코드')).toHaveValue('ABCD-2345')
    await fill('correct-horse', 'correct-horse')

    expect(signup).toHaveBeenCalledWith({ inviteCode: 'ABCD-2345', email: 'new@example.com', password: 'correct-horse' })
    expect(await screen.findByRole('status')).toHaveTextContent('확인 메일을 보냈습니다')
  })

  it('비밀번호 확인이 다르면 요청하지 않는다', async () => {
    renderRoutes([{ path: '/signup', element: <SignupPage /> }], '/signup')

    await userEvent.type(screen.getByLabelText('초대 코드'), 'ABCD-2345')
    await fill('correct-horse', 'different')

    expect(signup).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('비밀번호 확인이 일치하지 않습니다')
  })
})
```

`admin/src/pages/VerifyPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, verifyEmail } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { VerifyPage } from './VerifyPage'

vi.mock('../api/auth')

describe('VerifyPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('주소의 토큰으로 한 번만 확인하고 완료를 알린다', async () => {
    vi.mocked(verifyEmail).mockResolvedValue(undefined)
    renderRoutes([{ path: '/verify', element: <VerifyPage /> }], '/verify?token=tok123')

    expect(await screen.findByText(/가입이 끝났습니다/)).toBeInTheDocument()
    expect(verifyEmail).toHaveBeenCalledTimes(1)
    expect(verifyEmail).toHaveBeenCalledWith('tok123')
  })

  it('실패하면 서버 메시지를 보여 준다', async () => {
    vi.mocked(verifyEmail).mockRejectedValue(new ApiError(400, 'INVALID_TOKEN', '링크가 만료되었거나 이미 사용되었습니다'))
    renderRoutes([{ path: '/verify', element: <VerifyPage /> }], '/verify?token=old')

    expect(await screen.findByRole('alert')).toHaveTextContent('링크가 만료되었거나')
  })
})
```

`admin/src/pages/ResetPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, resetPassword } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { ResetPage } from './ResetPage'

vi.mock('../api/auth')

describe('ResetPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('새 비밀번호를 정하면 로그인 안내를 보여 준다', async () => {
    vi.mocked(resetPassword).mockResolvedValue(undefined)
    renderRoutes([{ path: '/reset', element: <ResetPage /> }], '/reset?token=tok123')

    await userEvent.type(screen.getByLabelText('새 비밀번호'), 'new-password')
    await userEvent.type(screen.getByLabelText('새 비밀번호 확인'), 'new-password')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 바꾸기' }))

    expect(resetPassword).toHaveBeenCalledWith('tok123', 'new-password')
    expect(await screen.findByRole('status')).toHaveTextContent('비밀번호를 바꿨습니다')
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin && npm test`
Expected: FAIL (`../api/auth`, `AuthContext`, 페이지 모듈 없음)

- [ ] **Step 3: API·인증 상태 구현**

`admin/src/api/client.ts` (전체 교체):

```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

interface ErrorBody {
  code: string
  message: string
}

const isErrorBody = (body: unknown): body is ErrorBody =>
  typeof body === 'object' && body !== null && 'code' in body && 'message' in body

const readCookie = (name: string): string | null => {
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith(`${name}=`))
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : null
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  // 서버가 내려준 XSRF-TOKEN 쿠키를 변경 요청 헤더로 돌려보낸다 (Spring Security CSRF)
  const csrf = method === 'GET' ? null : readCookie('XSRF-TOKEN')
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}) }
  const response = await fetch(path, { ...init, headers: { ...headers, ...init?.headers } })
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    const error = isErrorBody(body) ? body : { code: 'UNKNOWN', message: `요청 실패 (${response.status})` }
    throw new ApiError(response.status, error.code, error.message)
  }
  const text = await response.text()
  const body: unknown = text ? JSON.parse(text) : undefined
  return body as T
}
```

`admin/src/types.ts` 맨 위에 추가:

```ts
export interface Me {
  email: string
  admin: boolean
}
```

`admin/src/api/auth.ts`:

```ts
import type { Me } from '../types'
import { request } from './client'

export interface SignupInput {
  inviteCode: string
  email: string
  password: string
}

const post = <T>(path: string, body?: object): Promise<T> =>
  request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined })

export const fetchMe = (): Promise<Me> => request<Me>('/api/me')
export const login = (email: string, password: string): Promise<Me> => post<Me>('/api/auth/login', { email, password })
export const logout = (): Promise<void> => post<void>('/api/auth/logout')
export const signup = (input: SignupInput): Promise<void> => post<void>('/api/auth/signup', input)
export const verifyEmail = (token: string): Promise<void> => post<void>('/api/auth/verify', { token })
export const requestPasswordReset = (email: string): Promise<void> => post<void>('/api/auth/forgot', { email })
export const resetPassword = (token: string, password: string): Promise<void> => post<void>('/api/auth/reset', { token, password })
```

`admin/src/auth/AuthContext.tsx`:

```tsx
import { createContext, type ReactNode, useContext, useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { fetchMe } from '../api/auth'
import type { Me } from '../types'

interface AuthState {
  me: Me | null
  loading: boolean
  setMe: (me: Me | null) => void
}

const AuthContext = createContext<AuthState | null>(null)

// 첫 /api/me 호출이 로그인 여부 확인과 함께 XSRF-TOKEN 쿠키도 받아 온다
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchMe()
      .then(setMe)
      .catch(() => setMe(null))
      .finally(() => setLoading(false))
  }, [])

  return <AuthContext.Provider value={{ me, loading, setMe }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('AuthProvider 안에서만 쓸 수 있습니다')
  return state
}

export function RequireAuth({ admin = false, children }: { admin?: boolean; children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <p>불러오는 중…</p>
  if (!me) return <Navigate to="/login" replace />
  if (admin && !me.admin) return <Navigate to="/items" replace />
  return <>{children}</>
}
```

`admin/src/components/Header.tsx`:

```tsx
import { NavLink, useNavigate } from 'react-router-dom'
import { logout } from '../api/auth'
import { useAuth } from '../auth/AuthContext'

export function Header() {
  const { me, setMe } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout().catch(() => undefined)
    setMe(null)
    navigate('/login')
  }

  return (
    <header className="header">
      <h1>나라장터 알리미</h1>
      {me && (
        <nav>
          <NavLink to="/items">내 알림</NavLink>
          <NavLink to="/settings">공통 조건</NavLink>
          {me.admin && (
            <>
              <span className="badge admin">관리자</span>
              <NavLink to="/admin/invites">초대 코드</NavLink>
              <NavLink to="/admin/runs">실행 이력</NavLink>
            </>
          )}
          <span className="me">{me.email}</span>
          <button type="button" onClick={handleLogout}>
            로그아웃
          </button>
        </nav>
      )}
    </header>
  )
}
```

- [ ] **Step 4: 페이지 구현**

`admin/src/pages/LoginPage.tsx`:

```tsx
import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export function LoginPage() {
  const { setMe } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      setMe(await login(email, password))
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '로그인에 실패했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="로그인">
      <h2>로그인</h2>
      <form onSubmit={handleSubmit}>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button type="submit">로그인</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      <p>
        <Link to="/signup">초대 코드로 가입</Link> · <Link to="/forgot">비밀번호를 잊었어요</Link>
      </p>
    </section>
  )
}
```

`admin/src/pages/SignupPage.tsx`:

```tsx
import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { signup } from '../api/auth'
import { ApiError } from '../api/client'

export function SignupPage() {
  const [params] = useSearchParams()
  const [inviteCode, setInviteCode] = useState(params.get('code') ?? '')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (password !== confirm) return setError('비밀번호 확인이 일치하지 않습니다')
    setError(null)
    try {
      await signup({ inviteCode, email, password })
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '가입 요청에 실패했습니다')
    }
  }

  if (sent) {
    return (
      <section className="card auth" aria-label="가입">
        <p role="status">확인 메일을 보냈습니다. 메일의 링크를 눌러 가입을 마치세요. (24시간 유효)</p>
        <Link to="/login">로그인 화면으로</Link>
      </section>
    )
  }

  return (
    <section className="card auth" aria-label="가입">
      <h2>초대 코드로 가입</h2>
      <form onSubmit={handleSubmit}>
        <label>
          초대 코드
          <input value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} required />
        </label>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required />
        </label>
        <label>
          비밀번호 확인
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
        </label>
        <p className="muted">비밀번호는 8~72자입니다. 미리 만들어 둔 계정이 있으면 같은 이메일로 가입하면 이어받습니다.</p>
        <button type="submit">가입 요청</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  )
}
```

`admin/src/pages/VerifyPage.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { verifyEmail } from '../api/auth'
import { ApiError } from '../api/client'

export function VerifyPage() {
  const [params] = useSearchParams()
  const [state, setState] = useState<'pending' | 'done' | 'error'>('pending')
  const [error, setError] = useState<string | null>(null)
  // 토큰은 한 번만 쓸 수 있으므로 StrictMode 의 이펙트 재실행에서 두 번 보내지 않는다
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    verifyEmail(params.get('token') ?? '')
      .then(() => setState('done'))
      .catch((e: unknown) => {
        setError(e instanceof ApiError ? e.message : '이메일 확인에 실패했습니다')
        setState('error')
      })
  }, [params])

  return (
    <section className="card auth" aria-label="이메일 확인">
      <h2>이메일 확인</h2>
      {state === 'pending' && <p>확인하는 중…</p>}
      {state === 'done' && <p>가입이 끝났습니다. 이제 로그인하세요.</p>}
      {state === 'error' && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
```

`admin/src/pages/ForgotPage.tsx`:

```tsx
import { type FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { requestPasswordReset } from '../api/auth'
import { ApiError } from '../api/client'

export function ForgotPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      await requestPasswordReset(email)
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="비밀번호 재설정 요청">
      <h2>비밀번호 재설정</h2>
      {sent ? (
        <p role="status">가입된 이메일이면 재설정 링크를 보냈습니다. (30분 유효)</p>
      ) : (
        <form onSubmit={handleSubmit}>
          <label>
            이메일
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <button type="submit">재설정 링크 받기</button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
```

`admin/src/pages/ResetPage.tsx`:

```tsx
import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../api/auth'
import { ApiError } from '../api/client'

export function ResetPage() {
  const [params] = useSearchParams()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (password !== confirm) return setError('비밀번호 확인이 일치하지 않습니다')
    setError(null)
    try {
      await resetPassword(params.get('token') ?? '', password)
      setDone(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '비밀번호를 바꾸지 못했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="새 비밀번호">
      <h2>새 비밀번호 정하기</h2>
      {done ? (
        <p role="status">비밀번호를 바꿨습니다. 새 비밀번호로 로그인하세요.</p>
      ) : (
        <form onSubmit={handleSubmit}>
          <label>
            새 비밀번호
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required />
          </label>
          <label>
            새 비밀번호 확인
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
          </label>
          <button type="submit">비밀번호 바꾸기</button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
```

`admin/src/App.tsx` (전체 교체, 기존 화면은 로그인 뒤에 둔다. Task 14·15 에서 교체):

```tsx
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, RequireAuth } from './auth/AuthContext'
import { Header } from './components/Header'
import { FiltersPage } from './pages/FiltersPage'
import { ForgotPage } from './pages/ForgotPage'
import { LoginPage } from './pages/LoginPage'
import { ResetPage } from './pages/ResetPage'
import { RunsPage } from './pages/RunsPage'
import { SignupPage } from './pages/SignupPage'
import { VerifyPage } from './pages/VerifyPage'

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Header />
        <main>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/verify" element={<VerifyPage />} />
            <Route path="/forgot" element={<ForgotPage />} />
            <Route path="/reset" element={<ResetPage />} />
            <Route path="/filters" element={<RequireAuth><FiltersPage /></RequireAuth>} />
            <Route path="/runs" element={<RequireAuth admin><RunsPage /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/filters" replace />} />
          </Routes>
        </main>
      </AuthProvider>
    </BrowserRouter>
  )
}
```

`admin/src/styles.css` 끝에 추가:

```css
.auth {
  max-width: 420px;
  margin: 24px auto;
}

.auth form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}

.muted {
  color: #64748b;
  font-size: 13px;
}

.me {
  margin-left: 12px;
  color: #64748b;
  font-size: 13px;
}

nav button {
  margin-left: 12px;
}

.badge.admin {
  margin-left: 12px;
  background: #fef3c7;
  color: #92400e;
}
```

- [ ] **Step 5: 테스트·빌드 확인**

Run: `cd admin && npm test && npm run build`
Expected: 모든 테스트 PASS, 타입 검사·빌드 성공

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(admin): 로그인·초대 코드 가입·비밀번호 재설정 화면 추가

세션 로그인과 CSRF 헤더를 화면에 붙여 사용자별 알림 화면의 기반을 만들기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 13: 화면 — 발송 주기 편집기 (admin)

**Files:**
- Create: `admin/src/cadence.ts`, `admin/src/api/cron.ts`, `admin/src/hooks/useCronPreview.ts`, `admin/src/components/CadenceEditor.tsx`
- Modify: `admin/src/types.ts`, `admin/src/format.ts`, `admin/src/styles.css`
- Test: `admin/src/cadence.test.ts`, `admin/src/components/CadenceEditor.test.tsx`

**Interfaces:**
- Consumes: `GET /api/cron/preview?expr=` → `{ cron, next: string[] }`
- Produces:
  - `cadence.ts`: `type DaySet = 'WEEKDAYS' | 'WEEKENDS' | 'EVERYDAY'`, `type Interval = 5 | 30 | 60 | 120 | 'DAILY'`, `interface CadenceChoice { days; allDay: boolean; startHour: number; endHour: number; interval }`, `IMMEDIATE_CRON = '0 */5 * * * *'`, `DEFAULT_CHOICE`, `buildCron(choice): string`, `parseCron(cron): CadenceChoice | null`, `describeChoice(choice): string`, `describeCron(cron): string`
  - `types.ts`: `interface CronPreview { cron: string; next: string[] }`
  - `api/cron.ts`: `previewCron(expr): Promise<CronPreview>`
  - `hooks/useCronPreview.ts`: `useCronPreview(cron: string | null): { next: string[]; error: string | null }`
  - `format.ts`: `formatKstShort(iso): string`
  - `components/CadenceEditor.tsx`: `CadenceEditor({ value: string; onChange: (cron: string) => void })`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/src/cadence.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildCron, type CadenceChoice, describeCron, IMMEDIATE_CRON, parseCron } from './cadence'

const choice = (patch: Partial<CadenceChoice>): CadenceChoice => ({
  days: 'EVERYDAY', allDay: false, startHour: 9, endHour: 18, interval: 30, ...patch,
})

describe('buildCron', () => {
  it.each<[Partial<CadenceChoice>, string]>([
    [{ days: 'WEEKDAYS', interval: 30 }, '0 */30 9-17 * * MON-FRI'],
    [{ allDay: true, interval: 5 }, IMMEDIATE_CRON],
    [{ days: 'WEEKENDS', startHour: 9, endHour: 12, interval: 60 }, '0 0 9-11 * * SAT,SUN'],
    [{ interval: 120 }, '0 0 9-17/2 * * *'],
    [{ allDay: true, interval: 120 }, '0 0 */2 * * *'],
    [{ startHour: 9, endHour: 10, interval: 120 }, '0 0 9 * * *'],
    [{ days: 'WEEKDAYS', interval: 'DAILY' }, '0 0 9 * * MON-FRI'],
    [{ allDay: true, interval: 'DAILY' }, '0 0 0 * * *'],
  ])('%o → %s', (patch, cron) => {
    expect(buildCron(choice(patch))).toBe(cron)
  })
})

describe('parseCron', () => {
  it('편집기가 만든 식을 다시 선택값으로 읽는다', () => {
    expect(parseCron('0 */30 9-17 * * MON-FRI')).toEqual(choice({ days: 'WEEKDAYS', interval: 30 }))
    expect(parseCron(' 0  */5 * * * * ')).toMatchObject({ allDay: true, interval: 5 })
  })

  it('같은 식이 여러 선택으로 만들어지면 하루 1번으로 읽는다', () => {
    expect(parseCron('0 0 9 * * *')).toMatchObject({ interval: 'DAILY', startHour: 9 })
  })

  it('편집기로 만들 수 없는 식은 null', () => {
    expect(parseCron('0 15 10 * * *')).toBeNull()
    expect(parseCron('잘못된 식')).toBeNull()
  })

  it('만든 식은 모두 같은 식으로 되돌아온다', () => {
    const samples = [
      choice({}), choice({ allDay: true, interval: 60 }), choice({ days: 'WEEKENDS', startHour: 0, endHour: 24, interval: 5 }),
      choice({ startHour: 22, endHour: 24, interval: 120 }), choice({ days: 'WEEKDAYS', startHour: 18, interval: 'DAILY' }),
    ]
    samples.forEach((sample) => {
      const cron = buildCron(sample)
      const parsed = parseCron(cron)
      expect(parsed && buildCron(parsed)).toBe(cron)
    })
  })
})

describe('describeCron', () => {
  it.each([
    ['0 */30 9-17 * * MON-FRI', '평일 09:00~18:00 전까지, 30분마다'],
    [IMMEDIATE_CRON, '매일 하루 종일, 즉시(5분마다)'],
    ['0 0 9 * * *', '매일 09:00 하루 1번'],
    ['0 0 */2 * * SAT,SUN', '주말 하루 종일, 2시간마다'],
    ['0 15 10 * * *', '사용자 지정 (0 15 10 * * *)'],
  ])('%s → %s', (cron, text) => {
    expect(describeCron(cron)).toBe(text)
  })
})
```

`admin/src/components/CadenceEditor.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'
import { CadenceEditor } from './CadenceEditor'

vi.mock('../api/cron')

function Harness({ initial, onChange }: { initial: string; onChange: (cron: string) => void }) {
  const [cron, setCron] = useState(initial)
  return (
    <CadenceEditor
      value={cron}
      onChange={(next) => {
        setCron(next)
        onChange(next)
      }}
    />
  )
}

describe('CadenceEditor', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(previewCron).mockImplementation((expr: string) =>
      Promise.resolve({ cron: expr, next: ['2026-10-31T05:30:00Z', '2026-10-31T06:00:00Z', '2026-10-31T06:30:00Z'] }),
    )
  })

  it('현재 주기를 문장·다음 발송·cron 으로 보여 준다', async () => {
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={vi.fn()} />)

    expect(screen.getByText('📬 평일 09:00~18:00 전까지, 30분마다')).toBeInTheDocument()
    expect(await screen.findByText(/14:30/)).toBeInTheDocument()
    expect(screen.getByText('0 */30 9-17 * * MON-FRI')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '평일' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('요일·하루 종일·주기를 고르면 cron 을 만들어 알린다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: '주말' }))
    expect(onChange).toHaveBeenLastCalledWith('0 */30 9-17 * * SAT,SUN')

    await userEvent.click(screen.getByLabelText('하루 종일'))
    expect(onChange).toHaveBeenLastCalledWith('0 */30 * * * SAT,SUN')

    await userEvent.click(screen.getByRole('button', { name: '1시간마다' }))
    expect(onChange).toHaveBeenLastCalledWith('0 0 * * * SAT,SUN')
  })

  it('시작 시각을 끝 시각 이후로 옮기면 끝 시각이 따라간다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={onChange} />)

    await userEvent.selectOptions(screen.getByLabelText('시작 시각'), '20')

    expect(onChange).toHaveBeenLastCalledWith('0 */30 20 * * MON-FRI')
  })

  it('cron 을 직접 고치면 적용하고, 만들 수 없는 식은 사용자 지정으로 보인다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */5 * * * *" onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: '직접 편집' }))
    const input = screen.getByLabelText('cron 식')
    await userEvent.clear(input)
    await userEvent.type(input, '0 15 10 * * *')
    await userEvent.click(screen.getByRole('button', { name: '적용' }))

    expect(onChange).toHaveBeenLastCalledWith('0 15 10 * * *')
    expect(screen.getByText('📬 사용자 지정 (0 15 10 * * *)')).toBeInTheDocument()
  })

  it('서버가 거절한 cron 은 이유를 보여 준다', async () => {
    vi.mocked(previewCron).mockRejectedValue(new ApiError(400, 'INVALID_CRON', '발송 간격은 5분 이상이어야 합니다'))
    render(<Harness initial="0 * * * * *" onChange={vi.fn()} />)

    expect(await screen.findByText('발송 간격은 5분 이상이어야 합니다')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin && npm test`
Expected: FAIL (`./cadence`, `../api/cron`, `./CadenceEditor` 없음)

- [ ] **Step 3: 구현**

`admin/src/cadence.ts`:

```ts
export type DaySet = 'WEEKDAYS' | 'WEEKENDS' | 'EVERYDAY'
export type Interval = 5 | 30 | 60 | 120 | 'DAILY'

// 시간대는 정시 단위다. endHour 는 그 시각 "전까지"를 뜻한다 (09~18 → 17시대까지 발송)
export interface CadenceChoice {
  days: DaySet
  allDay: boolean
  startHour: number
  endHour: number
  interval: Interval
}

export const IMMEDIATE_CRON = '0 */5 * * * *'
export const DEFAULT_CHOICE: CadenceChoice = { days: 'EVERYDAY', allDay: true, startHour: 9, endHour: 18, interval: 5 }

const DOW: Record<DaySet, string> = { WEEKDAYS: 'MON-FRI', WEEKENDS: 'SAT,SUN', EVERYDAY: '*' }
const DAY_LABELS: Record<DaySet, string> = { WEEKDAYS: '평일', WEEKENDS: '주말', EVERYDAY: '매일' }
const INTERVAL_LABELS: Record<Exclude<Interval, 'DAILY'>, string> = {
  5: '즉시(5분마다)', 30: '30분마다', 60: '1시간마다', 120: '2시간마다',
}

const hourRange = (c: CadenceChoice): string =>
  c.endHour - 1 === c.startHour ? `${c.startHour}` : `${c.startHour}-${c.endHour - 1}`

export function buildCron(c: CadenceChoice): string {
  const dow = DOW[c.days]
  switch (c.interval) {
    case 5:
    case 30:
      return `0 */${c.interval} ${c.allDay ? '*' : hourRange(c)} * * ${dow}`
    case 60:
      return `0 0 ${c.allDay ? '*' : hourRange(c)} * * ${dow}`
    case 120: {
      // 한 시간짜리 구간에 /2 를 붙이면 "그 시각부터 2시간마다"가 되어 밤까지 이어지므로 붙이지 않는다
      const single = c.endHour - 1 === c.startHour
      const hours = c.allDay ? '*/2' : single ? `${c.startHour}` : `${hourRange(c)}/2`
      return `0 0 ${hours} * * ${dow}`
    }
    case 'DAILY':
      return `0 0 ${c.allDay ? 0 : c.startHour} * * ${dow}`
  }
}

// 편집기로 만들 수 있는 모든 선택을 미리 만들어 cron → 선택 표를 둔다. 같은 식은 먼저 나온 선택(하루 1번 우선)을 쓴다
const DAYS: DaySet[] = ['EVERYDAY', 'WEEKDAYS', 'WEEKENDS']
const INTERVALS: Interval[] = ['DAILY', 5, 30, 60, 120]
const BY_CRON = new Map<string, CadenceChoice>()
for (const days of DAYS) {
  for (const interval of INTERVALS) {
    const candidates: CadenceChoice[] = [{ ...DEFAULT_CHOICE, days, interval, allDay: true }]
    for (let startHour = 0; startHour < 24; startHour++) {
      for (let endHour = startHour + 1; endHour <= 24; endHour++) {
        candidates.push({ days, allDay: false, startHour, endHour, interval })
      }
    }
    candidates.forEach((candidate) => {
      const cron = buildCron(candidate)
      if (!BY_CRON.has(cron)) BY_CRON.set(cron, candidate)
    })
  }
}

export const parseCron = (cron: string): CadenceChoice | null => BY_CRON.get(cron.trim().replace(/\s+/g, ' ')) ?? null

const pad = (hour: number): string => `${String(hour).padStart(2, '0')}:00`

export function describeChoice(c: CadenceChoice): string {
  const day = DAY_LABELS[c.days]
  if (c.interval === 'DAILY') return `${day} ${pad(c.allDay ? 0 : c.startHour)} 하루 1번`
  const range = c.allDay ? '하루 종일' : `${pad(c.startHour)}~${pad(c.endHour)} 전까지`
  return `${day} ${range}, ${INTERVAL_LABELS[c.interval]}`
}

export function describeCron(cron: string): string {
  const parsed = parseCron(cron)
  return parsed ? describeChoice(parsed) : `사용자 지정 (${cron})`
}
```

`admin/src/types.ts` 에 추가:

```ts
export interface CronPreview {
  cron: string
  next: string[]
}
```

`admin/src/api/cron.ts`:

```ts
import type { CronPreview } from '../types'
import { request } from './client'

export const previewCron = (expr: string): Promise<CronPreview> =>
  request<CronPreview>(`/api/cron/preview?${new URLSearchParams({ expr }).toString()}`)
```

`admin/src/hooks/useCronPreview.ts`:

```ts
import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'

interface CronPreviewState {
  next: string[]
  error: string | null
}

// 다음 발송 시각과 cron 검증은 worker 와 같은 계산을 쓰도록 서버에 맡긴다
export function useCronPreview(cron: string | null): CronPreviewState {
  const [state, setState] = useState<CronPreviewState>({ next: [], error: null })

  useEffect(() => {
    if (cron === null) return
    let active = true
    previewCron(cron)
      .then((preview) => {
        if (active) setState({ next: preview.next, error: null })
      })
      .catch((e: unknown) => {
        if (active) setState({ next: [], error: e instanceof ApiError ? e.message : '미리보기를 불러오지 못했습니다' })
      })
    return () => {
      active = false
    }
  }, [cron])

  return state
}
```

`admin/src/format.ts` 에 추가:

```ts
const shortKst = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'numeric',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export const formatKstShort = (iso: string): string => shortKst.format(new Date(iso))
```

`admin/src/components/CadenceEditor.tsx`:

```tsx
import { useState } from 'react'
import { buildCron, type CadenceChoice, DEFAULT_CHOICE, type DaySet, describeCron, type Interval, parseCron } from '../cadence'
import { formatKstShort } from '../format'
import { useCronPreview } from '../hooks/useCronPreview'

interface CadenceEditorProps {
  value: string
  onChange: (cron: string) => void
}

const DAY_OPTIONS: { value: DaySet; label: string }[] = [
  { value: 'WEEKDAYS', label: '평일' },
  { value: 'WEEKENDS', label: '주말' },
  { value: 'EVERYDAY', label: '매일' },
]

const INTERVAL_OPTIONS: { value: Interval; label: string }[] = [
  { value: 5, label: '즉시 (5분마다)' },
  { value: 30, label: '30분마다' },
  { value: 60, label: '1시간마다' },
  { value: 120, label: '2시간마다' },
  { value: 'DAILY', label: '시작 시각에 하루 1번' },
]

const HOURS = Array.from({ length: 24 }, (_, hour) => hour)
const pad = (hour: number): string => `${String(hour).padStart(2, '0')}시`

export function CadenceEditor({ value, onChange }: CadenceEditorProps) {
  const parsed = parseCron(value)
  // 직접 만든 식이면 버튼은 기본값에서 다시 시작한다
  const choice = parsed ?? DEFAULT_CHOICE
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const preview = useCronPreview(value)

  const update = (patch: Partial<CadenceChoice>) => onChange(buildCron({ ...choice, ...patch }))

  return (
    <div className="cadence">
      <fieldset>
        <legend>① 요일</legend>
        <div className="row">
          {DAY_OPTIONS.map((option) => (
            <button key={option.value} type="button" aria-pressed={parsed?.days === option.value} onClick={() => update({ days: option.value })}>
              {option.label}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend>② 시간대</legend>
        <div className="row">
          <label>
            시작
            <select
              aria-label="시작 시각"
              value={choice.startHour}
              disabled={choice.allDay}
              onChange={(e) => {
                const startHour = Number(e.target.value)
                update({ startHour, endHour: Math.max(choice.endHour, startHour + 1) })
              }}
            >
              {HOURS.map((hour) => (
                <option key={hour} value={hour}>{pad(hour)}</option>
              ))}
            </select>
          </label>
          <label>
            끝
            <select
              aria-label="끝 시각"
              value={choice.endHour}
              disabled={choice.allDay || choice.interval === 'DAILY'}
              onChange={(e) => {
                const endHour = Number(e.target.value)
                update({ endHour, startHour: Math.min(choice.startHour, endHour - 1) })
              }}
            >
              {HOURS.map((hour) => (
                <option key={hour + 1} value={hour + 1}>{pad(hour + 1)}</option>
              ))}
            </select>
          </label>
          <label>
            <input type="checkbox" checked={parsed !== null && choice.allDay} onChange={(e) => update({ allDay: e.target.checked })} />
            하루 종일
          </label>
        </div>
      </fieldset>

      <fieldset>
        <legend>③ 주기</legend>
        <div className="row">
          {INTERVAL_OPTIONS.map((option) => (
            <button
              key={String(option.value)}
              type="button"
              aria-pressed={parsed?.interval === option.value}
              onClick={() => update({ interval: option.value })}
            >
              {option.label}
            </button>
          ))}
        </div>
      </fieldset>

      <div className="cadence-summary">
        <b>📬 {describeCron(value)}</b>
        {preview.next.length > 0 && <div className="muted">다음 발송: {preview.next.map(formatKstShort).join(' · ')}</div>}
        {preview.error && <p className="error">{preview.error}</p>}
        <div className="muted">
          cron <code>{value}</code>{' '}
          <button
            type="button"
            onClick={() => {
              setDraft(value)
              setEditing(true)
            }}
          >
            직접 편집
          </button>
        </div>
        {editing && (
          <div className="row">
            <input aria-label="cron 식" value={draft} onChange={(e) => setDraft(e.target.value)} />
            <button
              type="button"
              onClick={() => {
                onChange(draft.trim())
                setEditing(false)
              }}
            >
              적용
            </button>
            <button type="button" onClick={() => setEditing(false)}>
              취소
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
```

`admin/src/styles.css` 끝에 추가:

```css
.cadence fieldset {
  border: 0;
  padding: 0;
  margin: 0 0 12px;
}

.cadence legend {
  font-weight: 600;
  margin-bottom: 6px;
}

.cadence button[aria-pressed='true'] {
  background: #2563eb;
  color: #fff;
}

.cadence-summary {
  background: #f1f5f9;
  border-radius: 8px;
  padding: 12px;
}
```

- [ ] **Step 4: 테스트·빌드 확인**

Run: `cd admin && npm test && npm run build`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(admin): 요일·시간대·주기 3단계 발송 주기 편집기 추가

cron 을 몰라도 주기를 고르게 하고, 다음 발송 시각과 cron 식을 함께 보여 주기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---
### Task 14: 화면 — 내 알림 목록·추가·상세, 공통 조건 (admin)

**Files:**
- Create: `admin/src/api/items.ts`, `admin/src/api/settings.ts`, `admin/src/itemGroups.ts`
- Create: `admin/src/pages/{ItemsPage,NewItemPage,ItemDetailPage,SettingsPage}.tsx`
- Modify: `admin/src/types.ts`, `admin/src/format.ts`, `admin/src/App.tsx`, `admin/src/styles.css`
- Delete: `admin/src/pages/FiltersPage.tsx`, `admin/src/pages/FiltersPage.test.tsx`, `admin/src/components/TermListCard.tsx`, `admin/src/components/TermListCard.test.tsx`, `admin/src/components/SettingsCard.tsx`, `admin/src/components/SettingsCard.test.tsx`, `admin/src/api/filters.ts`
- Test: `admin/src/itemGroups.test.ts`, `admin/src/pages/{ItemsPage,NewItemPage,ItemDetailPage,SettingsPage}.test.tsx`

**Interfaces:**
- Consumes: Task 10 API, `CadenceEditor`, `describeCron`, `IMMEDIATE_CRON`, `useCronPreview`, `formatKstShort`
- Produces:
  - `types.ts`: `ItemType`, `Item`, `DeliveryStatus`, `NoticeSource`, `RecentNotice`, `ItemDetail`, `Settings` (아래 코드)
  - `api/items.ts`: `fetchItems()`, `fetchItem(id)`, `createItem({ type, value, cron })`, `updateItem(id, { value?, cron? })`, `deleteItem(id)`
  - `api/settings.ts`: `fetchSettings()`, `updateSettings(settings)`
  - `itemGroups.ts`: `groupByCron(items): ItemGroup[]`(다음 발송이 빠른 순), `summarizeSettings(settings): string`
  - `format.ts`: `formatPrice(n): string`, `formatLocal(text): string`(`2026-11-05T10:00:00` → `11/05 10:00`)

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/src/itemGroups.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { groupByCron, summarizeSettings } from './itemGroups'
import type { Item } from './types'

const item = (id: number, cron: string, nextSendAt: string | null): Item => ({
  id, type: 'KEYWORD', value: `키워드${id}`, cron, nextSendAt, createdAt: '2026-10-25T00:00:00Z',
})

describe('groupByCron', () => {
  it('같은 주기끼리 묶고 다음 발송이 빠른 묶음부터 놓는다', () => {
    const groups = groupByCron([
      item(1, '0 0 9 * * *', '2026-11-01T00:00:00Z'),
      item(2, '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(3, '0 0 9 * * *', '2026-11-01T00:00:00Z'),
    ])

    expect(groups.map((g) => g.cron)).toEqual(['0 */5 * * * *', '0 0 9 * * *'])
    expect(groups[1].items.map((i) => i.id)).toEqual([1, 3])
  })
})

describe('summarizeSettings', () => {
  it('제외 키워드 수·업무구분·가격 범위를 한 줄로 요약한다', () => {
    expect(summarizeSettings({ excludeKeywords: ['교육', '청소'], categories: ['GOODS', 'SERVICE', 'CONSTRUCTION', 'FOREIGN'], priceMin: null, priceMax: null }))
      .toBe('제외 키워드 2개 · 업무구분 전체 · 가격 제한 없음')
    expect(summarizeSettings({ excludeKeywords: [], categories: ['SERVICE'], priceMin: 1000, priceMax: null }))
      .toBe('제외 키워드 0개 · 업무구분 용역 · 가격 1,000원 이상')
  })
})
```

`admin/src/pages/ItemsPage.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchItems } from '../api/items'
import { fetchSettings } from '../api/settings'
import type { Item } from '../types'
import { ItemsPage } from './ItemsPage'

vi.mock('../api/items')
vi.mock('../api/settings')

const item = (id: number, type: Item['type'], value: string, cron: string, nextSendAt: string): Item => ({
  id, type, value, cron, nextSendAt, createdAt: '2026-10-25T00:00:00Z',
})

describe('ItemsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchSettings).mockResolvedValue({ excludeKeywords: ['교육'], categories: ['GOODS', 'SERVICE', 'CONSTRUCTION', 'FOREIGN'], priceMin: null, priceMax: null })
  })

  it('주기별 카드에 항목을 태그로 묶고 공통 조건을 요약한다', async () => {
    vi.mocked(fetchItems).mockResolvedValue([
      item(1, 'KEYWORD', 'ODA', '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(2, 'AGENCY', '한국국제협력단', '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(3, 'KEYWORD', '의료', '0 0 9 * * *', '2026-11-01T00:00:00Z'),
    ])
    render(<MemoryRouter><ItemsPage /></MemoryRouter>)

    const immediate = await screen.findByRole('article', { name: '매일 하루 종일, 즉시(5분마다)' })
    expect(within(immediate).getByRole('link', { name: 'ODA' })).toHaveAttribute('href', '/items/1')
    expect(within(immediate).getByRole('link', { name: '🏛 한국국제협력단' })).toHaveAttribute('href', '/items/2')
    expect(within(immediate).getByText(/2개/)).toBeInTheDocument()
    expect(screen.getByRole('article', { name: '매일 09:00 하루 1번' })).toBeInTheDocument()
    expect(screen.getByText(/제외 키워드 1개 · 업무구분 전체 · 가격 제한 없음/)).toBeInTheDocument()
  })

  it('항목이 없으면 안내를 보여 준다', async () => {
    vi.mocked(fetchItems).mockResolvedValue([])
    render(<MemoryRouter><ItemsPage /></MemoryRouter>)

    expect(await screen.findByText('아직 등록한 키워드·수요기관이 없습니다')).toBeInTheDocument()
  })
})
```

`admin/src/pages/NewItemPage.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'
import { createItem } from '../api/items'
import { IMMEDIATE_CRON } from '../cadence'
import { NewItemPage } from './NewItemPage'

vi.mock('../api/items')
vi.mock('../api/cron')

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/items/new']}>
      <Routes>
        <Route path="/items/new" element={<NewItemPage />} />
        <Route path="/items" element={<p>목록 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )

describe('NewItemPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(previewCron).mockResolvedValue({ cron: IMMEDIATE_CRON, next: [] })
  })

  it('수요기관을 기본 주기(즉시)로 추가하고 목록으로 돌아간다', async () => {
    vi.mocked(createItem).mockResolvedValue({ id: 9, type: 'AGENCY', value: '조달청', cron: IMMEDIATE_CRON, nextSendAt: null, createdAt: '' })
    renderPage()

    await userEvent.click(screen.getByLabelText('수요기관'))
    await userEvent.type(screen.getByLabelText('값'), '조달청')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(createItem).toHaveBeenCalledWith({ type: 'AGENCY', value: '조달청', cron: IMMEDIATE_CRON })
    expect(await screen.findByText('목록 화면')).toBeInTheDocument()
  })

  it('서버가 거절하면 이유를 보여 준다', async () => {
    vi.mocked(createItem).mockRejectedValue(new ApiError(409, 'DUPLICATE', '이미 등록된 키워드입니다: ODA'))
    renderPage()

    await userEvent.type(screen.getByLabelText('값'), 'ODA')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록된 키워드입니다: ODA')
  })
})
```

`admin/src/pages/ItemDetailPage.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { previewCron } from '../api/cron'
import { deleteItem, fetchItem, updateItem } from '../api/items'
import type { ItemDetail } from '../types'
import { ItemDetailPage } from './ItemDetailPage'

vi.mock('../api/items')
vi.mock('../api/cron')

const detail: ItemDetail = {
  item: { id: 7, type: 'KEYWORD', value: '타당성', cron: '0 */30 9-17 * * MON-FRI', nextSendAt: '2026-10-31T05:30:00Z', createdAt: '2026-10-25T00:00:00Z' },
  stats: { matched: 27, sent: 25, pending: 2 },
  recent: [
    {
      id: 2, status: 'PENDING', matchedAt: '2026-10-31T05:05:00Z', sentAt: null, scheduledAt: '2026-10-31T05:30:00Z',
      source: 'BID', noticeNo: 'R2', title: '도로 타당성조사', agency: '강원특별자치도', price: 240000000,
      deadlineAt: '2026-11-05T10:00:00', detailUrl: 'https://g2b/R2', revision: true,
    },
    {
      id: 1, status: 'SENT', matchedAt: '2026-10-30T23:00:00Z', sentAt: '2026-10-31T00:00:00Z', scheduledAt: null,
      source: 'PRE_SPEC', noticeNo: 'P1', title: '하수처리장 사전타당성', agency: null, price: null,
      deadlineAt: null, detailUrl: null, revision: false,
    },
  ],
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/items/7']}>
      <Routes>
        <Route path="/items/:id" element={<ItemDetailPage />} />
        <Route path="/items" element={<p>목록 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )

describe('ItemDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchItem).mockResolvedValue(detail)
    vi.mocked(previewCron).mockResolvedValue({ cron: detail.item.cron, next: ['2026-10-31T05:30:00Z'] })
  })

  it('설정·최근 7일 통계·최근 걸린 공고와 상태를 보여 준다', async () => {
    renderPage()

    expect(await screen.findByText('🏢 평일 09:00~18:00 전까지, 30분마다')).toBeInTheDocument()
    const stats = screen.getByRole('region', { name: '최근 7일' })
    expect(within(stats).getByText('27')).toBeInTheDocument()
    expect(within(stats).getByText('25')).toBeInTheDocument()
    const pendingRow = screen.getByRole('row', { name: /도로 타당성조사/ })
    expect(within(pendingRow).getByText(/⏳ 대기/)).toBeInTheDocument()
    expect(within(pendingRow).getByText(/14:30 예정/)).toBeInTheDocument()
    expect(within(pendingRow).getByRole('link', { name: '[정정] 도로 타당성조사' })).toHaveAttribute('href', 'https://g2b/R2')
    expect(within(pendingRow).getByText('240,000,000원')).toBeInTheDocument()
    expect(within(pendingRow).getByText('11/05 10:00')).toBeInTheDocument()
    const sentRow = screen.getByRole('row', { name: /하수처리장 사전타당성/ })
    expect(within(sentRow).getByText(/✅ 발송/)).toBeInTheDocument()
    expect(within(sentRow).getByText('사전규격')).toBeInTheDocument()
  })

  it('값을 고쳐 저장한다', async () => {
    vi.mocked(updateItem).mockResolvedValue(detail.item)
    renderPage()

    const input = await screen.findByLabelText('키워드')
    await userEvent.clear(input)
    await userEvent.type(input, '예비타당성')
    await userEvent.click(screen.getByRole('button', { name: '값 저장' }))

    expect(updateItem).toHaveBeenCalledWith(7, { value: '예비타당성' })
  })

  it('주기를 바꾸면 편집기에서 고른 cron 으로 저장한다', async () => {
    vi.mocked(updateItem).mockResolvedValue(detail.item)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: '변경' }))
    await userEvent.click(screen.getByRole('button', { name: '1시간마다' }))
    await userEvent.click(screen.getByRole('button', { name: '주기 저장' }))

    expect(updateItem).toHaveBeenCalledWith(7, { cron: '0 0 9-17 * * MON-FRI' })
  })

  it('삭제를 확인하면 지우고 목록으로 돌아간다', async () => {
    vi.mocked(deleteItem).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: '삭제' }))

    expect(deleteItem).toHaveBeenCalledWith(7)
    expect(await screen.findByText('목록 화면')).toBeInTheDocument()
  })
})
```

`admin/src/pages/SettingsPage.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchSettings, updateSettings } from '../api/settings'
import type { Settings } from '../types'
import { SettingsPage } from './SettingsPage'

vi.mock('../api/settings')

const settings: Settings = { excludeKeywords: ['교육'], categories: ['GOODS', 'SERVICE'], priceMin: null, priceMax: null }

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchSettings).mockResolvedValue(settings)
    vi.mocked(updateSettings).mockImplementation((next: Settings) => Promise.resolve(next))
  })

  it('제외 키워드를 더하고 빼고 업무구분·가격을 바꿔 한 번에 저장한다', async () => {
    render(<SettingsPage />)

    await userEvent.type(await screen.findByLabelText('제외 키워드 입력'), '청소')
    await userEvent.click(screen.getByRole('button', { name: '제외 키워드 추가' }))
    await userEvent.click(screen.getByRole('button', { name: '교육 삭제' }))
    await userEvent.click(screen.getByLabelText('물품'))
    await userEvent.type(screen.getByLabelText('최소 가격'), '1000')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(updateSettings).toHaveBeenCalledWith({ excludeKeywords: ['청소'], categories: ['SERVICE'], priceMin: 1000, priceMax: null })
    expect(await screen.findByRole('status')).toHaveTextContent('다음 수집부터 적용됩니다')
  })

  it('업무구분을 모두 끄면 저장하지 않는다', async () => {
    render(<SettingsPage />)

    await userEvent.click(await screen.findByLabelText('물품'))
    await userEvent.click(screen.getByLabelText('용역'))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(updateSettings).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('업무구분을 하나 이상 선택하세요')
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin && npm test`
Expected: FAIL (`../api/items`, `./itemGroups`, 페이지 모듈 없음)

- [ ] **Step 3: 타입·API·요약 구현**

`admin/src/types.ts` 에서 `TermType`, `Term`, `Filters` 를 지우고 다음을 추가한다:

```ts
export type ItemType = 'KEYWORD' | 'AGENCY'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'FAILED'
export type NoticeSource = 'BID' | 'PRE_SPEC'

export interface Item {
  id: number
  type: ItemType
  value: string
  cron: string
  nextSendAt: string | null
  createdAt: string
}

export interface RecentNotice {
  id: number
  status: DeliveryStatus
  matchedAt: string
  sentAt: string | null
  scheduledAt: string | null
  source: NoticeSource
  noticeNo: string
  title: string
  agency: string | null
  price: number | null
  deadlineAt: string | null
  detailUrl: string | null
  revision: boolean
}

export interface ItemDetail {
  item: Item
  stats: { matched: number; sent: number; pending: number }
  recent: RecentNotice[]
}

export interface Settings {
  excludeKeywords: string[]
  categories: Category[]
  priceMin: number | null
  priceMax: number | null
}

export const ITEM_TYPE_LABELS: Record<ItemType, string> = { KEYWORD: '키워드', AGENCY: '수요기관' }
export const SOURCE_LABELS: Record<NoticeSource, string> = { BID: '입찰', PRE_SPEC: '사전규격' }
```

`admin/src/api/items.ts`:

```ts
import type { Item, ItemDetail, ItemType } from '../types'
import { request } from './client'

export interface ItemInput {
  type: ItemType
  value: string
  cron: string
}

export interface ItemPatch {
  value?: string
  cron?: string
}

export const fetchItems = (): Promise<Item[]> => request<Item[]>('/api/items')
export const fetchItem = (id: number): Promise<ItemDetail> => request<ItemDetail>(`/api/items/${id}`)
export const createItem = (input: ItemInput): Promise<Item> => request<Item>('/api/items', { method: 'POST', body: JSON.stringify(input) })
export const updateItem = (id: number, patch: ItemPatch): Promise<Item> =>
  request<Item>(`/api/items/${id}`, { method: 'PATCH', body: JSON.stringify(patch) })
export const deleteItem = (id: number): Promise<void> => request<void>(`/api/items/${id}`, { method: 'DELETE' })
```

`admin/src/api/settings.ts`:

```ts
import type { Settings } from '../types'
import { request } from './client'

export const fetchSettings = (): Promise<Settings> => request<Settings>('/api/settings')
export const updateSettings = (settings: Settings): Promise<Settings> =>
  request<Settings>('/api/settings', { method: 'PUT', body: JSON.stringify(settings) })
```

`admin/src/format.ts` 에 추가:

```ts
export const formatPrice = (won: number): string => `${won.toLocaleString('ko-KR')}원`

// 서버의 LocalDateTime(시간대 없음, 이미 KST) 문자열을 그대로 줄여 보여 준다
export const formatLocal = (text: string): string => `${text.slice(5, 7)}/${text.slice(8, 10)} ${text.slice(11, 16)}`
```

`admin/src/itemGroups.ts`:

```ts
import { formatPrice } from './format'
import { CATEGORIES, CATEGORY_LABELS, type Item, type Settings } from './types'

export interface ItemGroup {
  cron: string
  nextSendAt: string | null
  items: Item[]
}

export function groupByCron(items: Item[]): ItemGroup[] {
  const groups = new Map<string, ItemGroup>()
  items.forEach((item) => {
    const group = groups.get(item.cron) ?? { cron: item.cron, nextSendAt: item.nextSendAt, items: [] }
    group.items.push(item)
    groups.set(item.cron, group)
  })
  const order = (group: ItemGroup): number => (group.nextSendAt ? Date.parse(group.nextSendAt) : Number.MAX_SAFE_INTEGER)
  return [...groups.values()].sort((a, b) => order(a) - order(b))
}

const priceRange = (min: number | null, max: number | null): string => {
  if (min === null && max === null) return '제한 없음'
  if (max === null) return `${formatPrice(min ?? 0)} 이상`
  if (min === null) return `${formatPrice(max)} 이하`
  return `${formatPrice(min)} ~ ${formatPrice(max)}`
}

export function summarizeSettings(settings: Settings): string {
  const categories = settings.categories.length === CATEGORIES.length
    ? '전체'
    : settings.categories.map((category) => CATEGORY_LABELS[category]).join('·')
  return `제외 키워드 ${settings.excludeKeywords.length}개 · 업무구분 ${categories} · 가격 ${priceRange(settings.priceMin, settings.priceMax)}`
}
```

- [ ] **Step 4: 페이지 구현**

`admin/src/pages/ItemsPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchItems } from '../api/items'
import { fetchSettings } from '../api/settings'
import { describeCron } from '../cadence'
import { formatKstShort } from '../format'
import { groupByCron, summarizeSettings } from '../itemGroups'
import type { Item, Settings } from '../types'

export function ItemsPage() {
  const [items, setItems] = useState<Item[] | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([fetchItems(), fetchSettings()])
      .then(([loadedItems, loadedSettings]) => {
        setItems(loadedItems)
        setSettings(loadedSettings)
      })
      .catch(() => setError('내 알림을 불러오지 못했습니다'))
  }, [])

  return (
    <section aria-label="내 알림">
      <div className="row between">
        <h2>
          내 알림 {items && <span className="count">{items.length}</span>}
        </h2>
        <Link className="button primary" to="/items/new">
          + 키워드 / 수요기관 추가
        </Link>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {items && items.length === 0 && <p>아직 등록한 키워드·수요기관이 없습니다</p>}
      {items &&
        groupByCron(items).map((group) => {
          const title = describeCron(group.cron)
          return (
            <article key={group.cron} className="card" aria-label={title}>
              <div className="row between">
                <b>{title}</b>
                <span className="muted">
                  {group.nextSendAt ? `다음 발송 ${formatKstShort(group.nextSendAt)}` : '다음 발송 없음'} · {group.items.length}개
                </span>
              </div>
              <div className="tags">
                {group.items.map((item) => (
                  <Link key={item.id} className="tag" to={`/items/${item.id}`}>
                    {item.type === 'AGENCY' ? `🏛 ${item.value}` : item.value}
                  </Link>
                ))}
              </div>
            </article>
          )
        })}
      {settings && (
        <p className="muted">
          공통 조건: {summarizeSettings(settings)} <Link to="/settings">편집</Link>
        </p>
      )}
    </section>
  )
}
```

`admin/src/pages/NewItemPage.tsx`:

```tsx
import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { createItem } from '../api/items'
import { IMMEDIATE_CRON } from '../cadence'
import { CadenceEditor } from '../components/CadenceEditor'
import type { ItemType } from '../types'

export function NewItemPage() {
  const navigate = useNavigate()
  const [type, setType] = useState<ItemType>('KEYWORD')
  const [value, setValue] = useState('')
  const [cron, setCron] = useState(IMMEDIATE_CRON)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      await createItem({ type, value, cron })
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '추가하지 못했습니다')
    }
  }

  return (
    <section className="card" aria-label="항목 추가">
      <h2>키워드 / 수요기관 추가</h2>
      <form onSubmit={handleSubmit}>
        <fieldset className="row">
          <legend>종류</legend>
          <label>
            <input type="radio" name="type" checked={type === 'KEYWORD'} onChange={() => setType('KEYWORD')} />
            키워드
          </label>
          <label>
            <input type="radio" name="type" checked={type === 'AGENCY'} onChange={() => setType('AGENCY')} />
            수요기관
          </label>
        </fieldset>
        <input aria-label="값" value={value} onChange={(e) => setValue(e.target.value)} placeholder={type === 'KEYWORD' ? '예: 타당성' : '예: 한국국제협력단'} />
        <p className="muted">
          {type === 'KEYWORD' ? '공고명' : '수요기관명'}에 이 단어가 포함되면 걸립니다 (대소문자 무시)
        </p>
        <h3>발송 주기</h3>
        <CadenceEditor value={cron} onChange={setCron} />
        <div className="row">
          <button type="submit">추가</button>
          <Link to="/items">취소</Link>
        </div>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  )
}
```

`admin/src/pages/ItemDetailPage.tsx`:

```tsx
import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { deleteItem, fetchItem, updateItem } from '../api/items'
import { describeCron } from '../cadence'
import { CadenceEditor } from '../components/CadenceEditor'
import { formatKstShort, formatLocal, formatPrice } from '../format'
import { useCronPreview } from '../hooks/useCronPreview'
import { ITEM_TYPE_LABELS, type ItemDetail, type RecentNotice, SOURCE_LABELS } from '../types'

function StatusCell({ notice }: { notice: RecentNotice }) {
  if (notice.status === 'SENT') return <>✅ 발송 {notice.sentAt && <span className="muted">{formatKstShort(notice.sentAt)}</span>}</>
  if (notice.status === 'FAILED') return <>❌ 실패</>
  return <>⏳ 대기 {notice.scheduledAt && <span className="muted">{formatKstShort(notice.scheduledAt)} 예정</span>}</>
}

export function ItemDetailPage() {
  const { id } = useParams()
  const itemId = Number(id)
  const navigate = useNavigate()
  const [detail, setDetail] = useState<ItemDetail | null>(null)
  const [value, setValue] = useState('')
  const [editingCron, setEditingCron] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const preview = useCronPreview(detail?.item.cron ?? null)

  const load = useCallback(
    () =>
      fetchItem(itemId)
        .then((loaded) => {
          setDetail(loaded)
          setValue(loaded.item.value)
        })
        .catch(() => setError('항목을 불러오지 못했습니다')),
    [itemId],
  )

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<unknown>, done: () => void) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      done()
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '저장하지 못했습니다')
    }
  }

  const handleValueSave = (event: FormEvent) => {
    event.preventDefault()
    void run(() => updateItem(itemId, { value }), () => setMessage('저장했습니다'))
  }

  const handleDelete = async () => {
    if (!detail || !window.confirm(`'${detail.item.value}'을(를) 삭제할까요? 대기 중인 공고도 더 이상 보내지 않습니다.`)) return
    try {
      await deleteItem(itemId)
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '삭제하지 못했습니다')
    }
  }

  if (!detail) return error ? <p role="alert" className="error">{error}</p> : <p>불러오는 중…</p>
  const { item, stats, recent } = detail
  const typeLabel = ITEM_TYPE_LABELS[item.type]

  return (
    <section aria-label="항목 상세">
      <Link to="/items">← 내 알림</Link>
      <div className="row between">
        <h2>
          <span className="badge">{item.type === 'KEYWORD' ? '🔑' : '🏛'} {typeLabel}</span> {item.value}
        </h2>
        <button type="button" className="danger" onClick={handleDelete}>
          삭제
        </button>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {message && <p role="status">{message}</p>}

      <div className="grid">
        <section className="card" aria-label="설정">
          <h3>설정</h3>
          <form className="row" onSubmit={handleValueSave}>
            <label>
              {typeLabel}
              <input value={value} onChange={(e) => setValue(e.target.value)} />
            </label>
            <button type="submit">값 저장</button>
          </form>
          <p className="muted">
            {item.type === 'KEYWORD' ? '공고명' : '수요기관명'}에 이 단어가 포함되면 걸립니다 (대소문자 무시). 공통 조건도 함께 적용됩니다.{' '}
            <Link to="/settings">공통 조건 편집</Link>
          </p>
          <h3>발송 주기</h3>
          {editingCron === null ? (
            <div className="cadence-summary">
              <b>🏢 {describeCron(item.cron)}</b>
              {preview.next.length > 0 && <div className="muted">다음 발송 {preview.next.map(formatKstShort).join(' · ')}</div>}
              <div className="muted">cron <code>{item.cron}</code></div>
              <button type="button" onClick={() => setEditingCron(item.cron)}>
                변경
              </button>
            </div>
          ) : (
            <>
              <CadenceEditor value={editingCron} onChange={setEditingCron} />
              <p className="muted">저장하면 대기 중인 공고에도 바로 적용됩니다.</p>
              <div className="row">
                <button type="button" onClick={() => void run(() => updateItem(itemId, { cron: editingCron }), () => setEditingCron(null))}>
                  주기 저장
                </button>
                <button type="button" onClick={() => setEditingCron(null)}>
                  취소
                </button>
              </div>
            </>
          )}
        </section>

        <section className="card" aria-label="최근 7일">
          <h3>최근 7일</h3>
          <div className="row stats">
            <div><b>{stats.matched}</b><span className="muted">걸린 공고</span></div>
            <div><b>{stats.sent}</b><span className="muted">발송</span></div>
            <div><b>{stats.pending}</b><span className="muted">대기</span></div>
          </div>
          <p className="muted">등록 {formatKstShort(item.createdAt)} · 다른 항목과 같이 걸린 공고는 더 빠른 주기로 1번만 발송됩니다.</p>
        </section>
      </div>

      <section className="card" aria-label="최근 걸린 공고">
        <h3>최근 걸린 공고 ({recent.length}건)</h3>
        {recent.length === 0 ? (
          <p className="muted">아직 걸린 공고가 없습니다</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>걸린 시각</th><th>상태</th><th>구분</th><th>공고명</th><th>수요기관</th><th>가격</th><th>마감</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((notice) => {
                const title = `${notice.revision ? '[정정] ' : ''}${notice.title}`
                return (
                  <tr key={notice.id}>
                    <td>{formatKstShort(notice.matchedAt)}</td>
                    <td><StatusCell notice={notice} /></td>
                    <td>{SOURCE_LABELS[notice.source]}</td>
                    <td>{notice.detailUrl ? <a href={notice.detailUrl} target="_blank" rel="noreferrer">{title}</a> : title}</td>
                    <td>{notice.agency ?? '-'}</td>
                    <td>{notice.price === null ? '-' : formatPrice(notice.price)}</td>
                    <td>{notice.deadlineAt ? formatLocal(notice.deadlineAt) : '-'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </section>
    </section>
  )
}
```

`admin/src/pages/SettingsPage.tsx`:

```tsx
import { type FormEvent, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { fetchSettings, updateSettings } from '../api/settings'
import { CATEGORIES, CATEGORY_LABELS, type Category, type Settings } from '../types'

const toNumber = (text: string): number | null => (text.trim() === '' ? null : Number(text))

function SettingsForm({ initial }: { initial: Settings }) {
  const [keywords, setKeywords] = useState(initial.excludeKeywords)
  const [input, setInput] = useState('')
  const [categories, setCategories] = useState<Category[]>(initial.categories)
  const [min, setMin] = useState(initial.priceMin?.toString() ?? '')
  const [max, setMax] = useState(initial.priceMax?.toString() ?? '')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const addKeyword = () => {
    const value = input.trim()
    if (value && !keywords.some((k) => k.toLowerCase() === value.toLowerCase())) setKeywords([...keywords, value])
    setInput('')
  }

  const toggle = (category: Category) =>
    setCategories((prev) => (prev.includes(category) ? prev.filter((c) => c !== category) : [...prev, category]))

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    const priceMin = toNumber(min)
    const priceMax = toNumber(max)
    if (categories.length === 0) return setError('업무구분을 하나 이상 선택하세요')
    if (priceMin !== null && priceMax !== null && priceMin > priceMax) return setError('최소 가격이 최대 가격보다 클 수 없습니다')
    setError(null)
    try {
      await updateSettings({ excludeKeywords: keywords, categories: CATEGORIES.filter((c) => categories.includes(c)), priceMin, priceMax })
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '저장하지 못했습니다')
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <section className="card" aria-label="제외 키워드">
        <h3>제외 키워드 <span className="count">{keywords.length}</span></h3>
        <p className="muted">공고명에 이 단어가 있으면 모든 항목에서 받지 않습니다.</p>
        <div className="row">
          <input aria-label="제외 키워드 입력" value={input} onChange={(e) => setInput(e.target.value)} placeholder="추가할 값" />
          <button type="button" onClick={addKeyword}>
            제외 키워드 추가
          </button>
        </div>
        <div className="tags">
          {keywords.map((keyword) => (
            <span key={keyword} className="tag">
              {keyword}
              <button type="button" aria-label={`${keyword} 삭제`} onClick={() => setKeywords(keywords.filter((k) => k !== keyword))}>
                ×
              </button>
            </span>
          ))}
        </div>
      </section>

      <section className="card" aria-label="업무구분 · 가격">
        <h3>업무구분 · 가격</h3>
        <fieldset className="row">
          <legend>업무구분</legend>
          {CATEGORIES.map((category) => (
            <label key={category}>
              <input type="checkbox" checked={categories.includes(category)} onChange={() => toggle(category)} />
              {CATEGORY_LABELS[category]}
            </label>
          ))}
        </fieldset>
        <div className="row">
          <label>
            최소 가격
            <input type="number" min={0} value={min} onChange={(e) => setMin(e.target.value)} />
          </label>
          <label>
            최대 가격
            <input type="number" min={0} value={max} onChange={(e) => setMax(e.target.value)} />
          </label>
        </div>
        <p className="muted">입찰공고는 추정가격, 사전규격은 배정예산 기준입니다. 가격이 없는 공고는 통과합니다.</p>
      </section>

      <button type="submit">저장</button>
      {error && <p role="alert" className="error">{error}</p>}
      {saved && <p role="status">저장했습니다. 다음 수집부터 적용됩니다</p>}
    </form>
  )
}

export function SettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchSettings()
      .then(setSettings)
      .catch(() => setError('공통 조건을 불러오지 못했습니다'))
  }, [])

  return (
    <section aria-label="공통 조건">
      <h2>공통 조건</h2>
      <p className="muted">모든 키워드·수요기관에 함께 적용됩니다.</p>
      {error && <p role="alert" className="error">{error}</p>}
      {settings && <SettingsForm initial={settings} />}
    </section>
  )
}
```

`admin/src/App.tsx` 를 최종 형태로 바꾼다(관리자 경로는 Task 15 에서 `InvitesPage` 를 더한다):

```tsx
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, RequireAuth } from './auth/AuthContext'
import { Header } from './components/Header'
import { ForgotPage } from './pages/ForgotPage'
import { ItemDetailPage } from './pages/ItemDetailPage'
import { ItemsPage } from './pages/ItemsPage'
import { LoginPage } from './pages/LoginPage'
import { NewItemPage } from './pages/NewItemPage'
import { ResetPage } from './pages/ResetPage'
import { RunsPage } from './pages/RunsPage'
import { SettingsPage } from './pages/SettingsPage'
import { SignupPage } from './pages/SignupPage'
import { VerifyPage } from './pages/VerifyPage'

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Header />
        <main>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/verify" element={<VerifyPage />} />
            <Route path="/forgot" element={<ForgotPage />} />
            <Route path="/reset" element={<ResetPage />} />
            <Route path="/items" element={<RequireAuth><ItemsPage /></RequireAuth>} />
            <Route path="/items/new" element={<RequireAuth><NewItemPage /></RequireAuth>} />
            <Route path="/items/:id" element={<RequireAuth><ItemDetailPage /></RequireAuth>} />
            <Route path="/settings" element={<RequireAuth><SettingsPage /></RequireAuth>} />
            <Route path="/admin/runs" element={<RequireAuth admin><RunsPage /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/items" replace />} />
          </Routes>
        </main>
      </AuthProvider>
    </BrowserRouter>
  )
}
```

```bash
git rm -q admin/src/pages/FiltersPage.tsx admin/src/pages/FiltersPage.test.tsx \
  admin/src/components/TermListCard.tsx admin/src/components/TermListCard.test.tsx \
  admin/src/components/SettingsCard.tsx admin/src/components/SettingsCard.test.tsx admin/src/api/filters.ts
```

`admin/src/styles.css` 끝에 추가:

```css
.row.between {
  justify-content: space-between;
  align-items: center;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 12px;
  background: #e0e7ff;
  color: #1e3a8a;
  text-decoration: none;
}

.button.primary {
  padding: 6px 12px;
  border-radius: 6px;
  background: #2563eb;
  color: #fff;
  text-decoration: none;
}

.stats > div {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  padding: 10px;
  background: #f8fafc;
  border-radius: 8px;
}

.stats b {
  font-size: 22px;
}

button.danger {
  color: #c62828;
}
```

- [ ] **Step 5: 테스트·빌드 확인**

Run: `cd admin && npm test && npm run build`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(admin): 주기별 내 알림 목록·항목 상세·공통 조건 화면 추가

기존 필터 화면을 사용자별 항목과 주기 중심으로 바꾸고, 최근 걸린 공고의 발송 상태를 보여 주기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 15: 화면 — 초대 코드 관리·실행 이력 (admin)

**Files:**
- Create: `admin/src/api/admin.ts`, `admin/src/pages/InvitesPage.tsx`
- Modify: `admin/src/api/runs.ts`, `admin/src/types.ts`, `admin/src/pages/RunsPage.tsx`, `admin/src/App.tsx`
- Test: `admin/src/pages/InvitesPage.test.tsx`, `admin/src/pages/RunsPage.test.tsx`(수정)

**Interfaces:**
- Consumes: Task 11 API, `GET /api/admin/runs`
- Produces:
  - `types.ts`: `JobName` 에 `'DELIVERY'` 추가, `JOB_LABELS.DELIVERY = '메일 발송'`, `InviteStatus`, `Invite`, `INVITE_STATUS_LABELS`
  - `api/admin.ts`: `fetchInvites()`, `createInvite({ maxUses, validDays })`, `revokeInvite(id)`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/src/pages/InvitesPage.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createInvite, fetchInvites, revokeInvite } from '../api/admin'
import type { Invite } from '../types'
import { InvitesPage } from './InvitesPage'

vi.mock('../api/admin')

const invite = (id: number, code: string, status: Invite['status']): Invite => ({
  id, code, status, maxUses: 5, usedCount: 1, expiresAt: '2026-11-07T04:40:00Z', createdAt: '2026-10-31T04:40:00Z',
})

describe('InvitesPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchInvites).mockResolvedValue([invite(2, 'B3HN-9WTE', 'ACTIVE'), invite(1, 'P8VF-6YJS', 'REVOKED')])
  })

  it('코드별 상태와 사용 횟수를 보여 주고 사용 가능한 코드만 회수 버튼이 있다', async () => {
    render(<InvitesPage />)

    const active = await screen.findByRole('row', { name: /B3HN-9WTE/ })
    expect(within(active).getByText('사용 가능')).toBeInTheDocument()
    expect(within(active).getByText('1 / 5')).toBeInTheDocument()
    expect(within(active).getByRole('button', { name: '회수' })).toBeInTheDocument()
    const revoked = screen.getByRole('row', { name: /P8VF-6YJS/ })
    expect(within(revoked).getByText('회수됨')).toBeInTheDocument()
    expect(within(revoked).queryByRole('button', { name: '회수' })).not.toBeInTheDocument()
  })

  it('만들면 새 코드와 가입 링크를 보여 준다', async () => {
    vi.mocked(createInvite).mockResolvedValue(invite(3, 'K7QM-2XPA', 'ACTIVE'))
    render(<InvitesPage />)

    const maxUses = await screen.findByLabelText('사용 한도')
    await userEvent.clear(maxUses)
    await userEvent.type(maxUses, '3')
    await userEvent.click(screen.getByRole('button', { name: '만들기' }))

    expect(createInvite).toHaveBeenCalledWith({ maxUses: 3, validDays: 7 })
    const created = await screen.findByRole('status')
    expect(created).toHaveTextContent('K7QM-2XPA')
    expect(created).toHaveTextContent('/signup?code=K7QM-2XPA')
  })

  it('회수를 확인하면 회수한다', async () => {
    vi.mocked(revokeInvite).mockResolvedValue(invite(2, 'B3HN-9WTE', 'REVOKED'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<InvitesPage />)

    const active = await screen.findByRole('row', { name: /B3HN-9WTE/ })
    await userEvent.click(within(active).getByRole('button', { name: '회수' }))

    expect(revokeInvite).toHaveBeenCalledWith(2)
  })
})
```

`admin/src/pages/RunsPage.test.tsx` 를 수정한다:

```bash
sed -i.bak 's/40 → 3 → 0/조회 40 → 새 공고 3 → 매칭 0/g' admin/src/pages/RunsPage.test.tsx && rm admin/src/pages/RunsPage.test.tsx.bak
```

그리고 `describe('RunsPage', ...)` 안에 테스트를 추가한다:

```tsx
  it('메일 발송 실행은 보낸 메일 수와 공고 수로 보여 준다', async () => {
    vi.mocked(fetchRuns).mockResolvedValue(page([{ ...run, id: 2, jobName: 'DELIVERY', fetchedCount: 0, matchedCount: 5, sentCount: 3, status: 'SUCCESS', error: null }]))
    render(<RunsPage />)

    const row = await screen.findByRole('row', { name: /메일 3통 · 공고 5건/ })
    expect(within(row).getByText('메일 발송')).toBeInTheDocument()
  })
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin && npm test`
Expected: FAIL (`../api/admin` 없음, RunsPage 건수 형식 다름)

- [ ] **Step 3: 구현**

`admin/src/types.ts` 를 수정한다: `export type JobName = 'BID_POLLING' | 'PRE_SPEC_DAILY' | 'DELIVERY'`, `JOB_LABELS` 에 `DELIVERY: '메일 발송'` 추가, 그리고 다음을 추가:

```ts
export type InviteStatus = 'ACTIVE' | 'EXHAUSTED' | 'EXPIRED' | 'REVOKED'

export interface Invite {
  id: number
  code: string
  status: InviteStatus
  maxUses: number
  usedCount: number
  expiresAt: string
  createdAt: string
}

export const INVITE_STATUS_LABELS: Record<InviteStatus, string> = {
  ACTIVE: '사용 가능', EXHAUSTED: '한도 소진', EXPIRED: '만료', REVOKED: '회수됨',
}
```

`admin/src/api/admin.ts`:

```ts
import type { Invite } from '../types'
import { request } from './client'

export interface InviteInput {
  maxUses: number
  validDays: number
}

export const fetchInvites = (): Promise<Invite[]> => request<Invite[]>('/api/admin/invites')
export const createInvite = (input: InviteInput): Promise<Invite> =>
  request<Invite>('/api/admin/invites', { method: 'POST', body: JSON.stringify(input) })
export const revokeInvite = (id: number): Promise<Invite> => request<Invite>(`/api/admin/invites/${id}/revoke`, { method: 'POST' })
```

`admin/src/api/runs.ts` 의 경로 `/api/runs?` → `/api/admin/runs?`.

`admin/src/pages/RunsPage.tsx` 를 수정한다:
- 작업 select 에 `<option value="DELIVERY">메일 발송</option>` 추가
- 헤더 `<th>조회 → 통과 → 발송</th>` → `<th>건수</th>`
- 셀 `{`${run.fetchedCount} → ${run.matchedCount} → ${run.sentCount}`}` → `{runCounts(run)}` 로 바꾸고, 컴포넌트 위에 함수를 추가:

```tsx
// 수집은 조회 → 새 공고 → 매칭(발송 대기 생성), 발송은 보낸 메일·공고 수
const runCounts = (run: JobRun): string =>
  run.jobName === 'DELIVERY'
    ? `메일 ${run.sentCount}통 · 공고 ${run.matchedCount}건`
    : `조회 ${run.fetchedCount} → 새 공고 ${run.matchedCount} → 매칭 ${run.sentCount}`
```

`admin/src/pages/InvitesPage.tsx`:

```tsx
import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { createInvite, fetchInvites, revokeInvite } from '../api/admin'
import { ApiError } from '../api/client'
import { formatKstShort } from '../format'
import { type Invite, INVITE_STATUS_LABELS } from '../types'

export function InvitesPage() {
  const [invites, setInvites] = useState<Invite[]>([])
  const [maxUses, setMaxUses] = useState('5')
  const [validDays, setValidDays] = useState('7')
  const [created, setCreated] = useState<Invite | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => fetchInvites().then(setInvites).catch(() => setError('초대 코드를 불러오지 못했습니다')), [])

  useEffect(() => {
    void load()
  }, [load])

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      setCreated(await createInvite({ maxUses: Number(maxUses), validDays: Number(validDays) }))
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '만들지 못했습니다')
    }
  }

  const handleRevoke = async (invite: Invite) => {
    if (!window.confirm(`'${invite.code}' 코드를 회수할까요? 이미 가입한 사람에게는 영향이 없습니다.`)) return
    try {
      await revokeInvite(invite.id)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '회수하지 못했습니다')
    }
  }

  const signupLink = (code: string) => `${window.location.origin}/signup?code=${code}`

  return (
    <section aria-label="초대 코드">
      <section className="card" aria-label="새 초대 코드">
        <h2>새 초대 코드 만들기</h2>
        <form className="row" onSubmit={handleCreate}>
          <label>
            사용 한도
            <input type="number" min={1} max={100} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
          </label>
          <label>
            유효 기간(일)
            <input type="number" min={1} max={30} value={validDays} onChange={(e) => setValidDays(e.target.value)} />
          </label>
          <button type="submit">만들기</button>
        </form>
        {created && (
          <div role="status" className="warning">
            만들었습니다: <code>{created.code}</code>
            <div className="muted">팀원에게 이 가입 링크를 전달하세요: {signupLink(created.code)}</div>
            <button type="button" onClick={() => void navigator.clipboard?.writeText(signupLink(created.code))}>
              링크 복사
            </button>
          </div>
        )}
      </section>
      {error && <p role="alert" className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>코드</th><th>상태</th><th>사용</th><th>만료</th><th>만든 날</th><th></th>
          </tr>
        </thead>
        <tbody>
          {invites.map((invite) => (
            <tr key={invite.id}>
              <td><code>{invite.code}</code></td>
              <td>{INVITE_STATUS_LABELS[invite.status]}</td>
              <td>{`${invite.usedCount} / ${invite.maxUses}`}</td>
              <td>{formatKstShort(invite.expiresAt)}</td>
              <td>{formatKstShort(invite.createdAt)}</td>
              <td>
                {invite.status === 'ACTIVE' && (
                  <button type="button" onClick={() => handleRevoke(invite)}>
                    회수
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
```

`admin/src/App.tsx` 에 `import { InvitesPage } from './pages/InvitesPage'` 와 라우트 `<Route path="/admin/invites" element={<RequireAuth admin><InvitesPage /></RequireAuth>} />` 를 `/admin/runs` 앞에 추가한다.

- [ ] **Step 4: 테스트·빌드 확인**

Run: `cd admin && npm test && npm run build`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
feat(admin): 초대 코드 관리 화면과 메일 발송 실행 이력 표시 추가

관리자가 화면에서 초대 코드를 만들고 회수하며, 발송 판정도 배치 동작 확인에 함께 보이게 하기 위함

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 16: 배포 구성 — api·worker 두 서비스 (compose·Caddy·Actions·문서)

**Files:**
- Modify: `docker-compose.yml`, `web/Caddyfile`, `.github/workflows/deploy.yml`, `deploy/remote-deploy.sh`, `.env.example`, `README.md`

**Interfaces:**
- Consumes: Task 1 의 `Dockerfile`(빌드 인자 `MODULE`)
- Produces: 이미지 `ghcr.io/wjdtn7823/nara-reminder-api`, `ghcr.io/wjdtn7823/nara-reminder-worker`, `ghcr.io/wjdtn7823/nara-reminder-web`; compose 서비스 `postgres`, `api`, `worker`, `web`

- [ ] **Step 1: docker-compose.yml 의 `app` 서비스를 `api`·`worker` 로 교체**

`app:` 블록 전체를 다음으로 바꾸고, `web.depends_on` 의 `app` 을 `api` 로 바꾼다:

```yaml
  api:
    # 화면 API·로그인. 비밀값은 DB 접속 정보와 디스코드 웹훅만 받는다
    image: ghcr.io/wjdtn7823/nara-reminder-api:latest
    build:
      context: .
      args:
        MODULE: api
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/nara
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      ADMIN_EMAIL: ${ADMIN_EMAIL:-}
      LEGACY_OWNER_EMAIL: ${LEGACY_OWNER_EMAIL:-}
      APP_BASE_URL: ${APP_BASE_URL:-http://localhost}
      DISCORD_WEBHOOK_URL: ${DISCORD_WEBHOOK_URL:-}
      JAVA_TOOL_OPTIONS: -Xmx160m
      TZ: Asia/Seoul
    healthcheck:
      # Flyway 마이그레이션이 끝나야 8080 이 열린다
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'"]
      interval: 10s
      timeout: 3s
      retries: 30
      start_period: 30s
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  worker:
    # 수집·매칭·발송·인증 메일. 외부에 포트를 열지 않는다
    image: ghcr.io/wjdtn7823/nara-reminder-worker:latest
    build:
      context: .
      args:
        MODULE: worker
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/nara
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      NARA_SERVICE_KEY: ${NARA_SERVICE_KEY}
      SMTP_HOST: ${SMTP_HOST}
      SMTP_PORT: ${SMTP_PORT:-587}
      SMTP_USERNAME: ${SMTP_USERNAME}
      SMTP_PASSWORD: ${SMTP_PASSWORD}
      MAIL_FROM: ${MAIL_FROM}
      DISCORD_WEBHOOK_URL: ${DISCORD_WEBHOOK_URL:-}
      JAVA_TOOL_OPTIONS: -Xmx192m
      TZ: Asia/Seoul
    depends_on:
      # 스키마는 api 가 만든다
      api:
        condition: service_healthy
    restart: unless-stopped
```

`web/Caddyfile` 의 `reverse_proxy app:8080` → `reverse_proxy api:8080`.

- [ ] **Step 2: 배포 워크플로**

`.github/workflows/deploy.yml`:
- `env:` 의 `IMAGE: ghcr.io/wjdtn7823/nara-reminder` 를 `IMAGE_PREFIX: ghcr.io/wjdtn7823/nara-reminder` 로 바꾼다.
- `build` 잡을 모듈별 matrix 로 바꾼다:

```yaml
  build:
    needs: [test, admin]
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: [api, worker]
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0
      - uses: docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f # v3.12.0
      - uses: docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9 # v3.7.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8 # v6.19.2
        with:
          context: .
          build-args: MODULE=${{ matrix.module }}
          push: true
          tags: |
            ${{ env.IMAGE_PREFIX }}-${{ matrix.module }}:latest
            ${{ env.IMAGE_PREFIX }}-${{ matrix.module }}:${{ github.sha }}
          labels: |
            org.opencontainers.image.source=${{ github.server_url }}/${{ github.repository }}
            org.opencontainers.image.revision=${{ github.sha }}
          cache-from: type=gha,scope=${{ matrix.module }}
          cache-to: type=gha,mode=max,scope=${{ matrix.module }}
```

- `build-web` 잡의 `${{ env.WEB_IMAGE }}` 는 그대로 둔다.

- [ ] **Step 3: VM 배포 스크립트**

`deploy/remote-deploy.sh` (전체 교체):

```bash
#!/usr/bin/env bash
# GitHub Actions 가 Azure Run Command 로 VM 에서 실행하는 배포 스크립트 (root 로 실행됨)
set -euo pipefail

cd /opt/nara-reminder
git fetch -q origin master
git checkout -q master
git reset -q --hard origin/master

# api 는 기동할 때 기존 필터 이관(V5)과 관리자 생성을 하므로, 값이 없으면 기존 서비스를 내리기 전에 멈춘다
for key in ADMIN_EMAIL LEGACY_OWNER_EMAIL APP_BASE_URL; do
  if ! grep -q "^${key}=." .env; then
    echo "${key} 가 .env 에 없습니다"
    exit 1
  fi
done

docker compose pull api worker web

# 분리 전 단일 app 컨테이너가 남아 있으면 이관 중 옛 방식으로 메일을 보내지 않도록 먼저 내린다
docker ps -aq --filter label=com.docker.compose.service=app | xargs -r docker rm -f > /dev/null

docker compose up -d --no-build --remove-orphans
docker image prune -f > /dev/null

# api 가 마이그레이션을 마치고 healthy 가 될 때까지 기다린다 (최대 3분)
for _ in $(seq 1 36); do
  [ "$(docker compose ps api --format '{{.Health}}')" = "healthy" ] && break
  sleep 5
done

# 기동 직후 설정 오류로 재시작 루프에 빠지지 않았는지 확인한다
sleep 20
if [ "$(docker compose ps api --format '{{.Health}}')" != "healthy" ]; then
  docker compose logs --tail 50 api
  exit 1
fi
for service in api worker web; do
  if [ "$(docker compose ps "$service" --format '{{.State}}')" != "running" ]; then
    docker compose logs --tail 50 "$service"
    exit 1
  fi
done
git log --oneline -1
echo "DEPLOY_OK"
```

- [ ] **Step 4: .env.example 과 README**

`.env.example` 에서 `MAIL_TO` 두 줄(주석 포함)을 지우고 `MAIL_FROM=` 아래에 추가한다:

```bash
# 첫 관리자 이메일. 비밀번호는 화면의 "비밀번호를 잊었어요" 로 정한다
ADMIN_EMAIL=
# 단일 사용자 시절 필터를 옮길 계정 이메일 (기존 DB 에 필터가 있으면 필수)
LEGACY_OWNER_EMAIL=
# 인증 메일 링크의 기준 주소 (예: https://<도메인>)
APP_BASE_URL=
```

`README.md` 를 고친다:
- `## 실행` 의 `docker compose logs -f app` → `docker compose logs -f api worker`
- `## 필터 규칙` 섹션 제목을 `## 알림 규칙` 으로 바꾸고 본문을 다음으로 교체:

```markdown
각 사용자가 화면(`https://<도메인>/items`)에서 자기 키워드·수요기관을 등록하고 항목마다 발송 주기를 정한다.

- 수집은 전역: 입찰공고 5분마다, 사전규격 매일 08:00. 새 공고를 `notice` 에 저장하고 사용자별 발송 대기(`delivery`)에 매칭한다.
- 매칭: 키워드(공고명) **또는** 수요기관 중 하나에 걸리고, 사용자 공통 조건(제외 키워드·업무구분·가격 범위)을 모두 통과해야 한다. 부분일치·대소문자 무시.
- 발송: worker 가 1분마다 "걸린 항목들의 cron 중 하나라도 매칭 시각 이후 첫 시각이 지났는지"로 판정해 사용자당 한 통으로 모아 보낸다. 여러 항목에 걸린 공고는 가장 빠른 주기로 1번만 나간다.
- 주기는 Spring cron 6필드(`초 분 시 일 월 요일`, KST). 화면의 3단계 편집기(요일·시간대·주기)가 만들어 주고 직접 편집도 된다. 5분보다 촘촘한 주기는 거절한다.
- 업무구분·가격 기준은 이전과 같다(입찰공고는 추정가격, 사전규격은 배정예산. 가격 없는 공고는 통과).
```

- `## 어드민 화면` 섹션을 다음으로 교체:

```markdown
## 화면

- `/items` 내 알림(주기별 묶음), `/items/:id` 항목 상세(최근 걸린 공고·발송 상태), `/settings` 공통 조건
- `/admin/invites` 초대 코드, `/admin/runs` 실행 이력 — 관리자만
- 가입: 관리자가 만든 초대 코드 + 이메일 확인. 첫 관리자는 `ADMIN_EMAIL` 로 만들어지며 "비밀번호를 잊었어요" 로 첫 비밀번호를 정한다.
- 로그인 세션은 api 메모리에 있어 배포하면 모두 로그아웃된다.
- 로컬 개발: api 를 8080 으로 띄운 뒤 `cd admin && npm install && npm run dev` (Vite 가 `/api` 를 8080 으로 프록시)
```

- `## 운영 참고` 에 추가:

```markdown
- 서버는 두 프로세스다. `api`(화면 API·로그인, DB 접속 정보와 웹훅만 받음)와 `worker`(수집·발송·인증 메일, 서비스키·SMTP 를 받음, 포트 없음). 같은 Gradle 저장소의 `api`/`worker` 모듈이고 PostgreSQL 로만 연결된다.
- 인증 메일(가입 확인·비밀번호 재설정)은 api 가 `outbox_mail` 에 넣고 worker 가 10초마다 보낸다.
- 하루 보낸 메일이 400통을 넘으면 디스코드로 경고한다(Gmail 하루 한도 약 500통).
```

- `## 배포 (Azure VM)` 의 1번 항목 이미지 이름을 `nara-reminder-api`, `nara-reminder-worker`, `nara-reminder-web` 으로 바꾸고, 롤백 항목 아래에 추가:

```markdown
- 다중 사용자 전환(api/worker 분리) 이전으로 되돌리면 안 된다. 전환 뒤에는 `notified_notice` 가 갱신되지 않으므로 옛 버전은 전환 기간에 나온 공고를 **다시 메일로 보낸다**. 문제가 생기면 이전 커밋의 api/worker 이미지 태그로 되돌린다.
- 새 이미지 패키지(`nara-reminder-api`, `nara-reminder-worker`)는 처음 푸시되면 비공개일 수 있다. VM 은 로그인 없이 받으므로 GitHub 패키지 설정에서 Public 으로 바꿔야 배포된다.
```

- [ ] **Step 5: 로컬 검증**

Run: `./gradlew test --no-daemon && (cd admin && npm test && npm run build)`
Expected: 모두 PASS

Run: `docker compose config -q && docker compose build api worker web`
Expected: 설정 오류 없음, 세 이미지 빌드 성공

Run (선택, 로컬 기동 확인): `.env` 에 `ADMIN_EMAIL=admin@example.com`, `LEGACY_OWNER_EMAIL=legacy@example.com`, `APP_BASE_URL=http://localhost` 를 넣고 `docker compose up -d` → `docker compose ps` 에서 api 가 healthy, worker·web 이 running 인지 확인, `curl -s -o /dev/null -w '%{http_code}' http://localhost/api/me` 가 `401`. 확인 후 `docker compose down` (볼륨 `-v` 는 쓰지 않는다).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
build(deploy): api·worker 두 서비스로 배포 구성 변경

비밀값을 worker 에만 두고 화면 API 와 수집·발송을 따로 띄우기 위해 compose·Caddy·배포 워크플로·문서를 바꿈

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

## 운영 반영 체크리스트 (구현 뒤, 사용자 확인 필요 — 서브에이전트가 하지 않는다)

1. PR 생성 → CI(test, admin) 통과 확인.
2. **VM `.env` 에 `ADMIN_EMAIL`, `LEGACY_OWNER_EMAIL`, `APP_BASE_URL` 추가** — run-command 로, 사용자 확인 후. 쓴 뒤 `/var/lib/waagent/run-command/download` 사본 shred. 값은 로그에 남기지 않는다.
3. 머지(사용자 확인) → Deploy 실행. 첫 푸시 뒤 `nara-reminder-api`, `nara-reminder-worker` 패키지를 Public 으로 전환(사용자) 후 Deploy 재실행.
4. 배포 후 확인: `/api/me` 401, `docker compose ps`(api healthy), `docker stats` 메모리, 실행 이력에 수집·발송 행, 관리자 비밀번호 재설정 메일 수신, 이관 계정 항목 72개.
5. 메모리가 1GB + 스왑으로 모자라면 VM 업그레이드 여부를 사용자가 정한다.
