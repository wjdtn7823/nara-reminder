# 어드민 화면 설계 (필터 관리 · 배치 실행 이력)

- 작성일: 2026-10-25
- 상태: 검토 대기
- 선행 스펙: `2026-10-24-nara-reminder-design.md`

## 1. 목적

브라우저에서 다음을 할 수 있게 한다.

1. 필터(키워드, 제외 키워드, 수요기관, 업무구분, 가격 범위) 등록·조회·수정·삭제. 저장 즉시 다음 수집부터 적용.
2. 배치(입찰공고 폴링, 사전규격 일일 수집) 실행 이력 조회.

### 결정 사항

| 항목 | 결정 |
|---|---|
| 사용자 | 본인 1명 |
| 접근 | 인터넷 공개 `https://nara-reminder.koreacentral.cloudapp.azure.com` |
| 인증 | **없음** (사용자 결정). 주소를 아는 누구나 필터를 변경할 수 있음을 수용한다. 이후 필요 시 Caddy `basic_auth` 또는 443 IP 제한을 추가한다. |
| 필터 저장소 | PostgreSQL. `config/application.yml` 의 `nara.filter` 는 1회 이관 후 제거 |
| 실행 이력 | 실행 1회 = 1행 요약. 90일 보관 |
| 화면 | React + TypeScript(Vite) 정적 빌드를 Caddy 가 서빙 (런타임 Node 없음) |

### 범위 밖

- 로그인·권한, 실행 이력별 발송 공고 목록, 수동 배치 실행 버튼, 메일 수신자 관리

## 2. 구조

```
인터넷 ──443/80──▶ web 컨테이너 (Caddy + admin 정적 파일)
                    ├─ /        → admin SPA (index.html, 없는 경로는 index.html 로 fallback)
                    └─ /api/*   → app:8080 (compose 내부 네트워크)
                                   │
                    app 컨테이너 (Spring Boot) ──▶ postgres
                      ├─ 기존 수집 스케줄러
                      └─ 어드민 REST API (/api)
```

- VM 호스트에 apt 로 설치한 Caddy 는 제거하고 compose 의 `web` 서비스로 옮긴다. 인증서는 `caddy_data` 볼륨에 보존한다.
- app 의 8080 은 호스트에 publish 하지 않는다(compose 내부에서만 접근).
- 사이트 주소는 환경변수 `SITE_ADDRESS` 로 받는다. 운영 `.env` 에 `SITE_ADDRESS=nara-reminder.koreacentral.cloudapp.azure.com`, 로컬 기본값 `:80`.

### 백엔드 변경

- 의존성 추가: `spring-boot-starter-web`. Spring Security·Bean Validation 은 추가하지 않는다(검증은 `FilterService` 에서 직접).
- `spring.main.web-application-type: none` 제거, `server.port: 8080`.
- 컨트롤러는 일반 함수(MVC 요청 스레드에서 JPA 직접 호출). 코루틴은 기존 수집 파이프라인에만 쓴다.

### 보안 (인증 외)

- Caddy 응답 헤더: `Strict-Transport-Security`, `Content-Security-Policy: default-src 'self'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`.
- 입력 검증(§4). 오류 응답에 스택트레이스·내부 정보를 노출하지 않는다.

## 3. 데이터 모델 (Flyway)

### V2__admin.sql

```sql
CREATE TABLE filter_term (
    id          BIGSERIAL    PRIMARY KEY,
    type        VARCHAR(20)  NOT NULL,   -- KEYWORD | EXCLUDE_KEYWORD | AGENCY
    value       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (type, value)
);

CREATE TABLE filter_setting (
    id          SMALLINT     PRIMARY KEY CHECK (id = 1),
    categories  VARCHAR(100) NOT NULL,   -- 쉼표 구분, 예: GOODS,SERVICE
    price_min   BIGINT,
    price_max   BIGINT,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE job_run (
    id             BIGSERIAL   PRIMARY KEY,
    job_name       VARCHAR(32) NOT NULL,  -- BID_POLLING | PRE_SPEC_DAILY
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    window_from    TIMESTAMPTZ NOT NULL,
    window_to      TIMESTAMPTZ NOT NULL,
    fetched_count  INT         NOT NULL,  -- API 조회 건수 (업무구분 합계)
    matched_count  INT         NOT NULL,  -- 필터 통과 + 미발송 건수
    sent_count     INT         NOT NULL,  -- 메일 발송·기록된 건수
    status         VARCHAR(10) NOT NULL,  -- SUCCESS | FAILURE
    error          TEXT
);
CREATE INDEX idx_job_run_started ON job_run (started_at DESC);
```

### V3__seed_filters.sql — yml 이관

`config/application.yml` 의 현재 값을 그대로 INSERT 한다(1회 실행).

- `filter_term`: KEYWORD 62건, AGENCY 10건, EXCLUDE_KEYWORD 0건 (값은 yml 순서대로, `ON CONFLICT DO NOTHING`)
- `filter_setting`: `(1, 'GOODS,SERVICE,CONSTRUCTION,FOREIGN', NULL, NULL, now())`

이관 후 `config/application.yml` 의 `nara.filter` 를 삭제하고, 파일에 남는 설정이 없으면 파일과 compose 마운트도 제거한다. README 필터 절은 "어드민 화면에서 관리"로 바꾼다.

## 4. 백엔드 컴포넌트

| 컴포넌트 | 책임 |
|---|---|
| `notice/FilterCriteria` | 기존 `NaraProperties.Filter` 를 옮긴 data class (keywords, excludeKeywords, categories, priceMin, priceMax, agencies). `matches()` 확장 함수는 이 타입으로 그대로 유지 |
| `filter/FilterTerm`, `FilterSetting` 엔티티 + Repository | 필터 저장 |
| `filter/FilterService` | `current(): FilterCriteria` (매 수집마다 DB 조회), 항목 추가·수정·삭제, 설정 저장, 검증 |
| `filter/FilterController` | `/api/filters` API |
| `job/JobRun` 엔티티 + Repository | 실행 이력 |
| `job/JobRunController` | `/api/runs` API |
| `job/JobRunCleanup` | 매일 03:00 KST, `started_at < now - 90d` 삭제 |
| `web/ApiExceptionHandler` | 검증 400, 중복 409, 없음 404 를 `{code, message}` 로 응답 |

`NaraProperties.filter` 는 제거한다. `NoticePipeline` 은 `props.filter` 대신 실행 시작 시 `filterService.current()` 를 1회 호출해 그 값으로 업무구분 호출과 매칭을 한다. 실행 종료 시(성공·실패 모두) `job_run` 1행을 저장한다. `job_run` 저장 실패는 ERROR 로그만 남기고 수집 결과(`job_state`)에 영향을 주지 않는다.

### 검증 규칙

- term 값: trim 후 1~100자, 공백만 불가. 같은 type 내 중복 불가(409, 대소문자 무시 비교).
- categories: 1개 이상, 정의된 4개 값만.
- price: 0 이상, `priceMin <= priceMax` (둘 다 있을 때).

## 5. REST API (인증 없음)

| 메서드 | 경로 | 요청 / 응답 |
|---|---|---|
| GET | `/api/filters` | `{keywords:[{id,value}], excludeKeywords:[…], agencies:[…], categories:[…], priceMin, priceMax}` |
| POST | `/api/filters/terms` | `{type, value}` → 201 `{id,type,value}` / 400 / 409 |
| PUT | `/api/filters/terms/{id}` | `{value}` → 200 / 400 / 404 / 409 |
| DELETE | `/api/filters/terms/{id}` | 204 / 404 |
| PUT | `/api/filters/settings` | `{categories, priceMin, priceMax}` → 200 / 400 |
| GET | `/api/runs?job=&status=&page=0&size=50` | `{items:[{id,jobName,startedAt,finishedAt,windowFrom,windowTo,fetchedCount,matchedCount,sentCount,status,error}], page, size, totalPages, totalElements}` 최신순, size 최대 100 |

시각은 ISO-8601(오프셋 포함)로 응답하고 화면에서 KST 로 표시한다.

## 6. 화면 (`admin/`, React + TypeScript + Vite)

- 라우트: `/filters`(기본), `/runs`. 상단 내비게이션.
- **필터**: 키워드·제외 키워드·수요기관 카드 3개 — 입력칸+[추가], 목록(개수, 목록 내 검색), 항목별 [수정](인라인)·[삭제](확인). 업무구분 체크박스 4개 + 최소/최대 가격 + [저장]. 결과·오류 메시지 표시(예: 409 → "이미 등록된 키워드입니다", 저장 성공 → "다음 수집(최대 5분)부터 적용됩니다").
- **실행 이력**: 작업·상태 필터, 표(시작 시각 KST, 작업, 조회 구간, 조회→통과→발송, 상태 배지, 소요 시간, 오류 펼치기), 50건 페이지네이션.
- 원칙: 함수형 컴포넌트 + Hooks, `any` 금지, named export. 의존성은 `react`, `react-dom`, `react-router` 만(테스트: `vitest`, `@testing-library/react`, `@testing-library/user-event`, `jsdom`).

## 7. 빌드·배포

- `web/Dockerfile`: `node:22-alpine` 에서 `admin/` 빌드 → `caddy:2-alpine` 에 `dist` 복사 + `web/Caddyfile`.
- CI(PR): Gradle 테스트 + `admin` 의 `npm ci && npm test && npm run build`.
- Deploy(master): 이미지 2개(`nara-reminder`, `nara-reminder-web`) 빌드·푸시 → VM 에서 `docker compose pull && up -d`.
- 최초 전환(1회, run-command): 호스트 Caddy `systemctl disable --now caddy && apt-get remove -y caddy`, `.env` 에 `SITE_ADDRESS` 추가. 전환 직후 web 컨테이너가 인증서를 새로 발급한다(Let's Encrypt 주간 한도 내).

## 8. 테스트

| 대상 | 방식 | 케이스 |
|---|---|---|
| 필터 API | `@SpringBootTest` + MockMvc + Testcontainers | 조회, 추가 201, 공백/101자 400, 중복 409(대소문자 무시), 수정, 없는 id 404, 삭제, 설정 저장, categories 0개·priceMin>priceMax 400 |
| V3 이관 | Testcontainers | 마이그레이션 후 KEYWORD 62, AGENCY 10, 설정 1행 |
| 실행 이력 API | 동일 | 최신순, job/status 필터, 페이지, size 상한 |
| NoticePipeline | MockK | 매 실행 `filterService.current()` 사용, 성공·실패 모두 job_run 1행과 건수, job_run 저장 실패가 job_state 에 영향 없음 |
| JobRunCleanup | Testcontainers | 90일 초과만 삭제 |
| 화면 | Vitest + RTL, fetch 는 `vi.fn()` | 목록 렌더, 추가/수정/삭제 호출, 409 메시지, 가격 검증, 이력 필터 변경 시 재조회 |

## 9. 남는 위험

- 인증이 없어 외부인이 필터를 지우면 알림이 끊긴다. 실행 이력의 `matched_count`/`sent_count` 가 장기간 0 이면 의심 신호로 본다.
- web 컨테이너 전환 시 인증서 재발급 동안 수 초~수십 초 HTTPS 가 응답하지 않을 수 있다(수집에는 영향 없음).
