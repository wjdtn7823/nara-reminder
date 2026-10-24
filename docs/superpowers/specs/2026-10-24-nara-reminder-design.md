# 나라장터 공고 알림 서버 설계

- 작성일: 2026-10-24
- 상태: 검토 대기

## 1. 목적

나라장터(G2B)에 새로 올라오는 **입찰공고**와 **사전규격 공개** 중 관심 조건에 맞는 것을 이메일로 받아본다.

### 사용자 / 범위

- 본인 또는 팀 전용. 회원 관리 없음.
- 조건·수신자 등 운영 설정은 `application.yml` + 환경변수로 관리한다.

### 성공 기준

1. 조건에 맞는 새 입찰공고가 등록 후 늦어도 다음 수집 주기(5분) 안에 메일로 도착한다. (API 반영 지연분은 제외)
2. 조건에 맞는 사전규격은 매일 08:00 KST 요약 메일로 도착한다.
3. 같은 공고(같은 차수)가 두 번 발송되지 않는다. 단, "메일 발송 성공 후 기록 저장 실패"라는 예외 상황에서는 1회 중복을 허용한다(누락보다 중복을 택함).
4. 수집이 반복 실패하면 에러 알림 메일을 받는다.

### 범위 밖 (향후 과제)

- 어드민 화면 (설정 수정 UI)
- 외부 접속 (Tailscale Funnel 또는 Cloudflare Tunnel)
- 다중 사용자 / 사용자별 구독
- 이메일 외 알림 채널 (Discord, Slack, 텔레그램)
- 발주계획 API

## 2. 기술 스택

- Kotlin 2.x, JDK 21, Spring Boot 3.x, Gradle Kotlin DSL
- Spring `@Scheduled` (suspend 함수 지원) + Kotlin Coroutines
- `WebClient` (API 호출), `spring-boot-starter-mail` (SMTP)
- PostgreSQL 16 + Spring Data JPA + Flyway
- 테스트: JUnit5, MockK, MockWebServer, Testcontainers
- 실행: Docker Compose (`app`, `postgres` 2개 서비스, postgres 데이터는 named volume)

## 3. 아키텍처

```
[Scheduler]
 ├─ BidPollingJob    (fixedDelay 5분)
 └─ PreSpecDailyJob  (cron 0 0 8 * * *, Asia/Seoul)
        │
        ▼
 NaraApiClient ──HTTP──▶ 공공데이터포털 (입찰공고정보서비스 / 사전규격정보서비스)
        │
        ▼
 NoticeFilter (순수 함수)
        │
        ▼
 NotifiedNoticeRepository ◀──▶ PostgreSQL (이미 보낸 공고 제외)
        │
        ▼
 MailNotifier ──SMTP──▶ 수신자
```

### 컴포넌트

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `NaraProperties` | `application.yml`/환경변수 바인딩 (`@ConfigurationProperties` data class). 설정 읽기 경로를 이 한 곳으로 모은다. | - |
| `NaraApiClient` | 입찰공고·사전규격 API 호출, 페이지 순회, 결과코드 검증, 재시도, 응답을 `Notice`로 변환 | `NaraProperties`, `WebClient` |
| `Notice` | 공통 공고 모델 (data class) | - |
| `NoticeFilter` | `Notice`가 필터 조건에 맞는지 판정 | `NaraProperties.filter` |
| `NotifiedNoticeRepository` | 발송 이력 조회·저장 | JPA |
| `JobStateRepository` | 작업별 마지막 성공 시각, 연속 실패 횟수 조회·저장 | JPA |
| `MailNotifier` | 공고 목록 메일, 에러 알림 메일 생성·발송 | `JavaMailSender` |
| `BidPollingJob` / `PreSpecDailyJob` | 조회 → 필터 → 중복 제외 → 발송 → 기록 오케스트레이션 | 위 전부 |

### `Notice` 모델

```kotlin
data class Notice(
    val source: NoticeSource,      // BID | PRE_SPEC
    val noticeNo: String,          // 입찰공고번호 / 사전규격등록번호
    val noticeOrd: String,         // 공고차수 (사전규격은 "0")
    val title: String,
    val category: NoticeCategory,  // GOODS | SERVICE | CONSTRUCTION | FOREIGN
    val estimatedPrice: Long?,
    val region: String?,
    val agency: String?,           // 수요기관명
    val postedAt: LocalDateTime,
    val deadlineAt: LocalDateTime?,// 입찰마감 / 의견등록마감
    val detailUrl: String,
    val isRevision: Boolean,       // 정정공고 여부 (차수가 최초 차수보다 큼)
)
```

## 4. 설정

```yaml
nara:
  service-key: ${NARA_SERVICE_KEY}
  mail:
    from: ${MAIL_FROM}
    to: [wjdtn7823@gmail.com]
  bid:
    poll-interval: 5m
    overlap: 30m              # 조회 구간 겹침
    initial-lookback: 1h      # 최초 실행 시 조회 시작점
    max-lookback: 7d          # 장기 중단 후 재개 시 최대 조회 구간
    alert-after-failures: 6   # 연속 실패 N회 시 에러 알림
  pre-spec:
    cron: "0 0 8 * * *"
    overlap: 1h
    initial-lookback: 24h
    max-lookback: 7d
    alert-after-failures: 1
  filter:
    keywords: [소프트웨어, 유지보수, AI]
    exclude-keywords: []
    categories: [SERVICE, GOODS]   # GOODS | SERVICE | CONSTRUCTION | FOREIGN
    price-min: 50000000
    price-max: null
    regions: []
    agencies: []

spring:
  mail:
    host: ${SMTP_HOST}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
```

비밀값(`NARA_SERVICE_KEY`, `SMTP_*`, DB 비밀번호)은 `.env`로 주입하고 저장소에 커밋하지 않는다. `.env.example`만 커밋한다.

### 필터 규칙

- 조건 간에는 **AND**로 결합한다.
- 목록 조건(`keywords`, `categories`, `regions`, `agencies`)은 목록 안의 값끼리 **OR**로 매칭하고, 목록이 비어 있으면 그 조건은 검사하지 않는다.
- `keywords`, `exclude-keywords`: 공고명 부분일치, 대소문자 무시. 제외 키워드가 하나라도 포함되면 탈락한다.
- `price-min` / `price-max`: 양 끝 값을 포함한다(이상/이하). 가격 조건이 설정돼 있는데 추정가격이 없는 공고는 통과시킨다(누락 방지).
- `regions`: 응답의 지역 필드 부분일치.
- `agencies`: 수요기관명 부분일치.
- `categories`: 입찰공고는 업무구분별 엔드포인트를 따로 호출하므로, 설정된 업무구분만 호출한다.

## 5. 데이터 모델 (Flyway)

```sql
CREATE TABLE notified_notice (
    source       VARCHAR(16)  NOT NULL,   -- BID | PRE_SPEC
    notice_no    VARCHAR(64)  NOT NULL,
    notice_ord   VARCHAR(16)  NOT NULL,
    title        VARCHAR(1000) NOT NULL,
    notified_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (source, notice_no, notice_ord)
);

CREATE TABLE job_state (
    job_name             VARCHAR(32) PRIMARY KEY,  -- BID_POLLING | PRE_SPEC_DAILY
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INT NOT NULL DEFAULT 0,
    alert_sent           BOOLEAN NOT NULL DEFAULT FALSE
);
```

정정공고는 차수가 다르므로 별도 행으로 기록되고, 새 알림 대상이 된다.

## 6. 데이터 흐름

### 입찰공고 (`BidPollingJob`, fixedDelay 5분)

1. 조회 구간 계산
   - `start = max(lastSuccessAt - overlap, now - maxLookback)`
   - 최초 실행(`lastSuccessAt` 없음): `start = now - initialLookback`
   - `end = now`
2. 설정된 업무구분마다 입찰공고 목록 API를 등록일시 기준으로 호출하고, 마지막 페이지까지 순회한다.
3. `NoticeFilter`로 매칭한다.
4. `notified_notice`에 이미 있는 `(source, noticeNo, noticeOrd)`는 제외한다.
5. 남은 공고가 0건이면 메일을 보내지 않는다.
6. 남은 공고가 1건 이상이면 메일 1통을 발송한다.
7. 발송에 성공하면 `notified_notice`에 기록한다.
8. 모든 업무구분 조회와 발송이 성공했을 때만 `lastSuccessAt = end`로 갱신하고, `consecutive_failures = 0`, `alert_sent = false`로 초기화한다.

### 사전규격 (`PreSpecDailyJob`, 매일 08:00 KST)

입찰공고와 같은 흐름이다. 차이는 다음과 같다.

- 조회 구간 계산식은 입찰공고와 같고, 겹침(`overlap`)은 1시간으로 둔다.
- 키: `(PRE_SPEC, 사전규격등록번호, "0")`

### 메일 형식

- 제목
  - 입찰공고: `[나라장터] 새 입찰공고 {N}건 ({MM/dd HH:mm})`
  - 사전규격: `[나라장터] 사전규격 {N}건 ({MM/dd})`
  - 에러 알림: `[나라장터 알림 서버] {작업명} 수집 실패 ({N}회 연속)`
- 본문: HTML 표. 열 구성은 공고명(나라장터 상세 링크, 정정공고는 앞에 `[정정]`), 업무구분, 수요기관, 추정가격, 마감일시(입찰마감 또는 의견등록마감)이다. 게시일 오름차순으로 정렬한다.
- 수신자는 `nara.mail.to` 전원이다. 에러 알림도 같은 수신자에게 보낸다.

## 7. 에러 처리

원칙: **실행 안에서 재시도하고, 그래도 실패하면 `lastSuccessAt`을 갱신하지 않는다. 그러면 다음 배치가 같은 구간을 다시 조회한다.** 이미 보낸 공고는 DB가 걸러내므로 다시 조회해도 중복 발송이 생기지 않는다.

| 상황 | 처리 |
|---|---|
| API 타임아웃 / 5xx / 연결 오류 | 지수 백오프(1s, 2s, 4s)로 최대 3회 재시도. 그래도 실패하면 해당 업무구분 실패 처리. |
| HTTP 200 + 결과코드가 정상이 아님 (서비스키 오류, 트래픽 초과 등) | 실패 처리. 재시도로 해결되지 않는 오류이므로 재시도 없이 즉시 실패시킨다. |
| 일부 업무구분만 실패 | 성공한 업무구분의 공고는 필터·발송·기록을 진행한다. 실행 결과는 실패로 처리하고 `lastSuccessAt`은 갱신하지 않는다. |
| 메일 발송 실패 | 3회 재시도. 그래도 실패하면 기록하지 않고, 실행 결과는 실패로 처리한다. |
| 발송 성공 후 기록 저장 실패 | 실패 처리. 다음 배치에서 1회 중복 발송될 수 있다(허용). |

### 에러 알림

- 실행이 실패하면 `consecutive_failures`를 1 증가시킨다.
- `consecutive_failures >= alert-after-failures`이고 `alert_sent = false`이면 에러 알림 메일을 1회 보내고 `alert_sent = true`로 바꾼다. 같은 장애로는 메일을 반복해서 보내지 않는다.
- 에러 알림 본문에는 작업명, 연속 실패 횟수, 마지막 성공 시각, 마지막 오류 메시지(API 결과코드/메시지 또는 예외 메시지)를 넣는다.
- 성공하면 카운터와 `alert_sent`를 초기화한다. 다음 장애가 오면 다시 알림을 보낸다.
- 에러 알림 메일 발송 자체가 실패하면 ERROR 로그만 남긴다.
- 모든 실패는 ERROR 레벨로 로그를 남긴다.

## 8. 테스트

| 대상 | 방식 | 케이스 |
|---|---|---|
| `NoticeFilter` | 단위 테스트 | 키워드 OR, 제외 키워드, 가격 경계값, 가격 없음 통과, 빈 조건 통과, 지역/기관 부분일치 |
| `NaraApiClient` | MockWebServer + 응답 샘플 JSON | 정상 파싱, 페이지 순회, 200 + 에러 결과코드, 5xx 재시도 후 성공/실패, 정정 여부 판정 |
| `BidPollingJob` / `PreSpecDailyJob` | MockK | 0건이면 메일 없음, 중복 제외, 메일 실패 시 미기록·`lastSuccessAt` 유지, 일부 업무구분 실패, 조회 구간 계산(최초/정상/장기 중단), 연속 실패 알림 1회만 발송, 성공 시 초기화 |
| Repository | Testcontainers PostgreSQL | 발송 이력 저장/조회, PK 중복, `job_state` 갱신 |
| `MailNotifier` | MockK (`JavaMailSender`) | 제목 형식, 정정 표시, 정렬, 0건 미발송 |

## 9. 배포

- `docker-compose.yml`: `app`(멀티 스테이지 Dockerfile로 빌드), `postgres:16-alpine`(named volume, healthcheck). `app`은 `depends_on: condition: service_healthy`로 설정한다.
- 타임존: 컨테이너와 JVM 모두 `Asia/Seoul`.
- `.env.example`로 필요한 환경변수 목록을 제공한다.

## 10. 구현 계획 단계에서 확인할 항목

API 명세는 공공데이터포털 문서로 확인한 뒤 확정한다.

- 입찰공고정보서비스: 업무구분별 오퍼레이션명, 등록일시 조회 파라미터(`inqryDiv`, `inqryBgnDt`, `inqryEndDt`) 형식, 페이지 최대 건수, 지역/수요기관/추정가격/차수 필드명, 상세 URL 필드
- 사전규격정보서비스: 업무구분별 오퍼레이션명과 필드명, 의견등록마감 필드
- 계정 유형별 일일 트래픽 한도. 5분 주기 × 업무구분 수 × 페이지 수가 한도를 넘으면 운영계정 전환이 필요한지 판단하고 README에 안내한다.
- 정정공고 판정 기준(차수 값의 시작값과 형식)
