# 다중 사용자 알림 설계 (개인별 항목 · 발송 주기 · 서버 분리)

- 작성일: 2026-10-30
- 상태: 승인됨 (2026-10-31)
- 선행 스펙: `2026-10-24-nara-reminder-design.md`, `2026-10-25-admin-design.md`

## 1. 목적

1. 팀원 각자가 가입해 자기 키워드·수요기관을 등록하고 알림을 받는다.
2. 키워드·수요기관마다 메일 발송 주기를 cron 으로 정하되, cron 을 몰라도 화면에서 쉽게 설정한다.
3. 기존 필터 설정 화면을 주기별 목록 + 상세 화면으로 개선한다.

### 결정 사항

| 항목 | 결정 |
|---|---|
| 사용자 | 팀 최대 10명. **초대 코드**로 본인이 가입(사용 한도·만료·회수), 이메일 확인 필수 |
| 로그인 | 이메일 + 비밀번호(세션 쿠키). 비밀번호 재설정은 메일 링크 |
| 관리자 | 같은 `app_user` 테이블의 `is_admin`. 최초 관리자는 `.env` 의 `ADMIN_EMAIL` 로 1회 생성 |
| 개인 설정 | 키워드·수요기관 **항목마다 cron 주기**. 제외 키워드·업무구분·가격은 사용자 공통 |
| 주기의 의미 | 메일 **발송** 리듬. 수집은 전역으로 지금과 같음(입찰공고 5분, 사전규격 매일 08:00) |
| 사전규격 | 입찰공고와 같은 항목·주기를 따른다 |
| 중복 | 한 공고가 한 사용자의 여러 항목에 걸리면 가장 빠른 주기로 1번만 발송 |
| 수신 | 가입 이메일로 발송. 개인별 디스코드는 없음 |
| 서버 | **worker**(수집·매칭·발송·인증 메일 발송)와 **api**(REST·로그인·화면 API)로 분리. PostgreSQL 로만 연결 |
| 코드 구조 | Gradle 멀티 모듈 `core` / `api` / `worker` |
| 디스코드 | api·worker 가 **같은 웹훅** 사용 |
| 기존 데이터 | 키워드 62개·수요기관 10개를 `LEGACY_OWNER_EMAIL` 계정으로 이관(주기 즉시). 해당 사용자가 같은 이메일로 가입하면 계정을 이어받음 |

### 범위 밖

- 바뀐 모듈만 골라 배포하는 선택 배포(후속 작업. 이번에는 api·worker 를 항상 함께 배포)
- 개인별 디스코드 알림, 사용자 비활성화 화면, 세션 영속화(Spring Session JDBC)
- 저장소 공개/비공개 전환
- 관리자용 사용자 목록, 오늘 발송량 표시
- Gmail 이외의 발송 수단(경고가 실제로 오면 SMTP 설정만 바꿔 발송 서비스로 전환)

## 2. 구조

```
인터넷 ─443/80─▶ web (Caddy + SPA)
                  ├─ /       → SPA (없는 경로는 index.html)
                  └─ /api/*  → api:8080
                                 │
                 api (Spring MVC + Security, 스케줄러 없음) ──┐
                                                              ├──▶ PostgreSQL
                 worker (웹 서버 없음, 포트 없음) ────────────┘
                   · 수집(5분 / 08:00) → 매칭 → 발송 판정(1분)
                   · outbox 인증 메일 발송(10초)
                   · 정리 작업(03:00)
```

### Gradle 모듈

| 모듈 | 내용 | 의존 |
|---|---|---|
| `core` | 엔티티·리포지토리, Flyway 마이그레이션(V1~V5), cron 판정·검증(`Cadence`), 시간 유틸(`Time`), 디스코드 웹훅·ERROR 로그 appender | — |
| `api` | Spring Security, 컨트롤러, 가입·인증·초대 코드, 관리자 부트스트랩 | core |
| `worker` | 나라장터 클라이언트, 수집 파이프라인, 매칭, 발송, 메일, outbox 처리, 정리 작업 | core |

- 모듈마다 Spring Boot 애플리케이션 클래스를 하나씩 둔다. `worker` 는 `spring.main.web-application-type=none`.
- 이미지는 `nara-reminder-api`, `nara-reminder-worker` 두 개. Dockerfile 하나에 빌드 인자 `MODULE` 로 고른다.
- 기존 `src/` 코드는 위 표에 맞춰 옮긴다. `filter/` 패키지와 `FiltersPage` 는 새 항목·공통 조건 기능으로 대체되어 삭제한다.

### 비밀값 배치

| 환경변수 | api | worker |
|---|---|---|
| DB 접속 정보 | ✅ | ✅ |
| `ADMIN_EMAIL`, `LEGACY_OWNER_EMAIL`, `APP_BASE_URL` | ✅ | `APP_BASE_URL` 만 |
| `DISCORD_WEBHOOK_URL` | ✅ | ✅ |
| `NARA_SERVICE_KEY`, SMTP | — | ✅ |

`.env` 하나를 `api.env`, `worker.env` 로 나눈다. `MAIL_TO` 는 없어진다(수신자는 사용자 이메일).

## 3. 데이터 모델 (Flyway)

### V4 (SQL) — 새 테이블

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `app_user` | id, email(유일, 소문자 저장), password_hash(nullable, bcrypt), is_admin, status(ACTIVE/DISABLED), failed_count, locked_until, created_at | password_hash 가 null 이면 아직 주인이 가입하지 않은 계정 |
| `invite_code` | id, code(유일), max_uses, used_count, expires_at, revoked_at, created_by, created_at | |
| `email_token` | id, email, purpose(VERIFY/RESET), token_hash(유일, SHA-256), password_hash(VERIFY 만), invite_code_id(VERIFY 만), expires_at, used_at, created_at | VERIFY 는 확인 전까지 사용자 행을 만들지 않으므로 email 기준 |
| `watch_item` | id, user_id, type(KEYWORD/AGENCY), value, cron, created_at | 유일: (user_id, type, lower(value)) |
| `user_filter` | user_id(PK), exclude_keywords(text[]), categories(CSV), price_min, price_max | 사용자당 1행. 가입 시 기본값(제외 없음·전체 업무구분·가격 제한 없음)으로 생성 |
| `notice` | (source, bid_no, ord) PK, title, agency, price, deadline, url, published_at, collected_at | title 외 내용 컬럼은 nullable(이관 행 때문) |
| `delivery` | id, user_id, (source, bid_no, ord) FK → notice, status(PENDING/SENT/FAILED), attempts, matched_at, sent_at | 유일: (user_id, source, bid_no, ord) |
| `delivery_item` | delivery_id, watch_item_id (PK 둘 다) | watch_item 삭제 시 cascade |
| `outbox_mail` | id, to_address, subject, html, status(PENDING/SENT/FAILED), attempts, created_at, sent_at | |

`job_state`, `job_run` 은 그대로 쓴다. `JobName` 에 `DELIVERY` 를 추가한다.

### V5 (Kotlin `BaseJavaMigration`) — 기존 데이터 이관

공개 저장소라 개인 메일을 SQL 에 쓰지 않는다. 환경변수 `LEGACY_OWNER_EMAIL` 을 읽는다.

1. `filter_term` 에 행이 있는데 `LEGACY_OWNER_EMAIL` 이 비어 있으면 **마이그레이션을 실패**시켜 api 기동을 막는다(데이터가 주인 없이 남는 것 방지). `filter_term` 이 비어 있으면(새 설치) 아무것도 하지 않는다.
2. `app_user` 에 password_hash 없는 ACTIVE 계정 생성.
3. `filter_term` 의 KEYWORD·AGENCY → `watch_item`, cron `0 */5 * * * *`.
4. EXCLUDE_KEYWORD + `filter_setting`(업무구분·가격) → `user_filter`.
5. `notified_notice` 의 공고 키·제목 → `notice`. 매칭은 `notice` 에 새로 들어온 공고만 대상으로 하므로, 전환 직후 조회 구간이 겹쳐도 이미 보낸 공고는 다시 오지 않는다. `delivery` 로는 옮기지 않는다(상세 화면의 "최근 걸린 공고"는 전환 이후부터).

기존 `filter_term`, `filter_setting`, `notified_notice` 는 롤백 여지를 위해 이번에는 지우지 않고, 안정화 후 별도 PR 에서 삭제한다.

## 4. 수집 → 매칭 → 발송 (worker)

### 수집

입찰공고 5분, 사전규격 매일 08:00. 조회 구간(`QueryWindow`)·재시도·실패 알림은 지금과 같다. 가져온 공고는 `notice` 에 insert 하고, 이미 있는 키(같은 공고번호·차수)는 건너뛴다. 새로 들어간 공고만 매칭으로 넘긴다.

### 매칭

새 공고마다 ACTIVE 사용자(주인이 아직 가입하지 않은 계정 포함)에 대해 다음을 모두 만족하면 걸린 것으로 본다.

- 공통 조건 통과: 제목에 제외 키워드 없음 AND 업무구분 포함 AND 가격 범위 안 (기존 `FilterCriteria.matches()` 규칙과 같음. 가격 없는 공고는 가격 조건 통과)
- 항목 하나 이상: KEYWORD 는 제목 포함, AGENCY 는 수요기관 포함, 대소문자 무시

걸리면 `delivery`(PENDING, matched_at=now) 1행과 걸린 항목마다 `delivery_item` 을 만든다. 공고 저장·매칭·`job_state` 갱신은 한 트랜잭션이다. 중간에 실패하면 조회 구간이 넘어가지 않아 다음 실행에서 다시 처리된다.

### 발송 판정 (1분마다, `fixedDelay`)

> PENDING 공고는 **걸린 항목들의 cron 중 하나라도 `next(matched_at) ≤ now`** 이면 발송 대상이다.

- cron 은 Spring `CronExpression` 6필드(`초 분 시 일 월 요일`), Asia/Seoul 기준.
- 여러 항목에 걸린 공고는 가장 빠른 주기로 1번만 나간다.
- worker 재시작·배포로 판정을 건너뛰어도 다음 판정에서 나간다.
- 다음 발송 시각을 저장하지 않으므로 주기를 바꾸면 바로 반영된다.
- 걸린 항목이 모두 삭제된 PENDING 공고는 판정 때 지운다.

### 발송

- 발송 대상을 사용자별로 묶어 **사용자당 메일 1통**. 표 형식은 지금 메일과 같다(가격 열은 출처에 따라 추정가격/배정예산).
- 성공하면 SENT·sent_at. 실패하면 attempts+1 하고 다음 판정에서 재시도. 3번 실패하면 FAILED 로 바꾸고 디스코드 알림.
- 메일 전송 후 DB 기록 전에 죽으면 1번 중복 발송될 수 있다. 누락보다 중복을 택한다.
- 그날(KST) 보낸 메일 수(알림 + 인증 메일)가 **400통을 넘으면** 디스코드로 하루 1번 경고(Gmail 한도 약 500명/일).
- 실제로 보냈거나 실패가 있었던 판정만 `job_run`(DELIVERY)에 남긴다.

### 인증 메일 (outbox)

api 가 `outbox_mail` 에 넣고, worker 가 10초마다 PENDING 을 보낸다. 3번 실패하면 FAILED + 디스코드 알림.

### 정리 (03:00)

90일이 지난 `notice`(그 공고의 `delivery` 포함), `job_run`, 사용 완료·만료된 `email_token`, 보낸 `outbox_mail` 을 지운다. 기존 `JobRunCleanup` 을 넓힌다.

## 5. 인증·가입·초대 코드 (api)

### 로그인

- Spring Security 폼 로그인을 JSON API 로 쓴다. 세션 쿠키 HttpOnly·Secure·SameSite=Lax.
- CSRF: `CookieCsrfTokenRepository`(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더).
- 비밀번호 bcrypt, 8자 이상.
- 같은 계정 5번 연속 실패 시 15분 잠금(`failed_count`, `locked_until`). 성공하면 초기화.
- 세션은 api 메모리. 배포하면 모두 로그아웃된다(수용).
- password_hash 가 없는 계정, DISABLED 계정은 로그인 불가.

### 가입

1. 초대 코드·이메일·비밀번호 입력. 코드가 유효(만료·회수·한도 아님)한지 확인. 이미 비밀번호가 있는 이메일이면 "이미 가입된 이메일입니다"(409).
2. `email_token`(VERIFY, 비밀번호 해시·초대 코드 포함, 24시간) 저장, 확인 메일을 outbox 에 넣는다. 사용자 행은 아직 만들지 않는다.
3. 링크를 누르면 토큰과 초대 코드를 다시 확인하고, 초대 코드 used_count+1 후:
   - 없는 이메일 → 새 계정 + 기본 `user_filter` 생성
   - password_hash 없는 계정 → **이어받기**(비밀번호 설정, 기존 항목 유지)
   - 이미 비밀번호가 있음 → 409

### 비밀번호 재설정

이메일을 받으면 계정 유무와 관계없이 항상 같은 응답("메일을 보냈습니다"). 계정이 있으면 RESET 토큰(30분, 1회) 메일을 outbox 에 넣는다. 재설정하면 잠금도 풀린다. password_hash 없는 계정에도 동작하며, 관리자는 이 방법으로 첫 비밀번호를 정한다.

### 토큰과 링크

토큰은 32바이트 난수(URL-safe Base64). DB 에는 SHA-256 만 저장. 링크는 `APP_BASE_URL` + `/verify?token=…`, `/reset?token=…`.

### 관리자

- api 기동 시 `ADMIN_EMAIL` 계정이 없으면 password_hash 없이 `is_admin=true` 로 만든다. 있으면 `is_admin=true` 만 보장한다.
- 관리자 전용: 초대 코드 생성(사용 한도, 유효 일수)·목록·회수, 실행 이력.

## 6. REST API (api, `/api` 아래)

| 메서드·경로 | 설명 | 권한 |
|---|---|---|
| `POST auth/login`, `POST auth/logout` | 로그인·로그아웃 | 공개 / 로그인 |
| `POST auth/signup`, `POST auth/verify` | 가입 요청, 이메일 확인 | 공개 |
| `POST auth/forgot`, `POST auth/reset` | 재설정 메일 요청, 비밀번호 재설정 | 공개 |
| `GET me` | 내 이메일·관리자 여부 | 로그인 |
| `GET items` | 내 항목 목록. 각 항목에 `nextSendAt` 포함(묶음은 화면이 함) | 로그인 |
| `POST items`, `GET/PATCH/DELETE items/{id}` | 항목 추가·상세·수정(값, cron)·삭제. 상세에는 최근 7일 매칭·발송·대기 건수와 최근 걸린 공고 20건(상태, 발송 시각 또는 예정 발송 시각, 구분, 공고명·링크, 수요기관, 가격, 마감) | 로그인, 본인 것만(아니면 404) |
| `GET settings`, `PUT settings` | 공통 조건 | 로그인 |
| `GET cron/preview?expr=` | cron 검증 + 다음 발송 3회 | 로그인 |
| `GET/POST admin/invites`, `POST admin/invites/{id}/revoke` | 초대 코드 | 관리자(아니면 403) |
| `GET admin/runs` | 실행 이력(기존 `/api/runs` 이동). 작업 필터에 메일 발송 추가, 발송 판정 행은 보낸 메일 수·공고 수 표시 | 관리자 |

검증:

- 항목 값 1~100자, 같은 사용자·같은 종류에서 대소문자 무시 중복이면 409.
- cron 은 `CronExpression` 으로 파싱되어야 하고, 첫 발송 10회 사이 간격이 모두 5분 이상이어야 한다(수집이 5분이므로). 아니면 400.
- 오류 응답은 지금처럼 `{code, message}`.

## 7. 화면 (`admin/`, React)

| 경로 | 내용 |
|---|---|
| `/login`, `/signup`, `/verify`, `/forgot`, `/reset` | 로그인, 초대 코드 가입, 이메일 확인, 비밀번호 재설정 |
| `/items` | 주기별 카드(같은 cron 끼리 묶음), 항목은 태그. 카드마다 주기 요약·다음 발송 시각·항목 수. 아래에 공통 조건 한 줄 요약 + 편집 링크 |
| `/items/new` | 종류·값·주기(기본 즉시) |
| `/items/:id` | 값 수정(포함 조건 안내), 주기 요약·다음 발송 3회·cron 과 변경 버튼(편집기 팝업), 삭제, 공통 조건 안내·편집 링크. 최근 7일 걸린/발송/대기 건수. 최근 걸린 공고 20건: 걸린 시각, 상태(발송 시각 또는 대기의 예정 발송 시각, 실패), 구분(입찰/사전규격), 공고명(원문 링크), 수요기관, 가격, 마감 |
| `/settings` | 제외 키워드, 업무구분, 가격 범위 |
| `/admin/invites`, `/admin/runs` | 관리자 전용 |

상단에 내 이메일·로그아웃, 관리자에게만 관리자 메뉴. 로그인하지 않았으면 `/login` 으로 보낸다.

### 주기 편집기 (`CadenceEditor`)

① 요일(평일/주말/매일) ② 시간대(정시 단위 시작~끝, 또는 하루 종일) ③ 주기(즉시 5분/30분/1시간/2시간/하루 1번).

| 선택 | cron |
|---|---|
| 요일 | 평일 `MON-FRI`, 주말 `SAT,SUN`, 매일 `*` |
| 5분·30분 | `0 */N 시작-(끝-1) * * 요일` (하루 종일이면 시 필드 `*`) |
| 1시간·2시간 | `0 0 시작-(끝-1)/N * * 요일` (하루 종일이면 `*/N`) |
| 하루 1번 | `0 0 시작 * * 요일` (하루 종일이면 시작 0시) |

- 끝 시각 직전까지 보낸다. 화면에 "18:00 전까지"처럼 적는다.
- 문장 요약("평일 09:00~18:00 전까지, 30분마다 모아서 발송"), 다음 발송 3회(`cron/preview`), cron 문자열을 보여 주고 cron 은 직접 편집할 수 있다.
- 직접 고친 cron 이 위 규칙으로 만들 수 없는 식이면 ①②③을 "사용자 지정"으로 표시하고 미리보기만 보여 준다.

## 8. 배포

- compose 서비스: postgres, **api**(profile 없이 api 이미지, `-Xmx160m`, `api.env`), **worker**(`-Xmx192m`, `worker.env`, 포트 없음), web(`/api/*` → `api:8080`). 기존 `app` 은 없어진다.
- Flyway 는 api 만 실행(worker 는 `spring.flyway.enabled=false`). worker 는 api 가 healthy 가 된 뒤 뜬다(`depends_on: condition: service_healthy`).
- `deploy.yml` 은 api·worker·web 이미지를 모두 빌드하고, `remote-deploy.sh` 는 세 서비스를 모두 받아 띄운다. 헬스 체크: api 는 HTTP, worker 는 60초 동안 재시작 없이 running.
- 운영 VM 의 `.env` → `api.env`·`worker.env` 분리와 `ADMIN_EMAIL`·`LEGACY_OWNER_EMAIL`·`APP_BASE_URL` 추가는 배포 전에 run-command 로 한다(사용자 확인 후, 작업 후 run-command 사본 shred).
- 배포 후 `docker stats` 로 메모리 확인. 1GB + 스왑 2GB 로 부족하면 VM 업그레이드 여부를 사용자가 정한다.
- README 롤백 절차에 추가: 전환 이후에는 `notified_notice` 가 갱신되지 않으므로 이전 버전으로 되돌리면 전환 기간 공고가 다시 발송된다.

## 9. 테스트

백엔드(JUnit5 + MockK, Testcontainers PostgreSQL):

- 발송 판정: 가장 빠른 주기 선택, 판정을 건너뛴 뒤에도 발송, 주기 변경 즉시 반영, 항목 모두 삭제된 공고 제거
- 매칭: 공통 조건 AND + 항목 OR, 이미 있는 공고는 매칭하지 않음, 사용자당 1건
- 발송: 사용자별 1통, 실패 재시도와 3회 후 FAILED, 400통 경고
- 가입: 초대 코드 만료·회수·한도, 이어받기, 이미 가입된 이메일 409
- 보안: 토큰 만료·재사용 거부, 로그인 잠금, 남의 항목 404, 관리자 API 403, CSRF 토큰 없는 요청 거부
- cron 검증: 5분 미만 간격 거부
- V5 이관: 항목·공통 조건·공고 키가 옮겨지고, 환경변수 없이 데이터가 있으면 실패

프론트엔드(Vitest + RTL):

- `CadenceEditor`: 선택 조합별 cron 표 검증, 직접 편집 시 "사용자 지정"
- 주기별 묶음 목록, 상세 화면, 로그인·가입 흐름, 비관리자에게 관리자 메뉴 숨김

## 10. 남는 위험

- 세션이 메모리라 배포 때마다 로그아웃된다.
- 메일 전송 직후 장애 시 중복 발송 가능.
- Gmail 하루 한도. 경고만 하고 다른 발송 수단은 없다.
- 두 JVM 이 1GB VM 에서 모자랄 수 있다.
- 저장소가 공개라 V3 시드의 키워드·수요기관 목록은 이력에 남는다.
