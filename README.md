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
docker compose up -d --build
docker compose logs -f api worker
```

## 알림 규칙

각 사용자가 화면(`https://<도메인>/items`)에서 자기 키워드·수요기관을 등록하고 항목마다 발송 주기를 정한다.

- 수집은 전역: 입찰공고 5분마다, 사전규격 매일 08:00. 새 공고를 `notice` 에 저장하고 사용자별 발송 대기(`delivery`)에 매칭한다.
- 매칭: 키워드(공고명) **또는** 수요기관 중 하나에 걸리고, 사용자 공통 조건(제외 키워드·업무구분·가격 범위)을 모두 통과해야 한다. 부분일치·대소문자 무시.
- 발송: worker 가 1분마다 "걸린 항목들의 cron 중 하나라도 매칭 시각 이후 첫 시각이 지났는지"로 판정해 사용자당 한 통으로 모아 보낸다. 여러 항목에 걸린 공고는 가장 빠른 주기로 1번만 나간다.
- 주기는 Spring cron 6필드(`초 분 시 일 월 요일`, KST). 화면의 3단계 편집기(요일·시간대·주기)가 만들어 주고 직접 편집도 된다. 5분보다 촘촘한 주기는 거절한다.
- 업무구분·가격 기준은 이전과 같다(입찰공고는 추정가격, 사전규격은 배정예산. 가격 없는 공고는 통과).

## 화면

- `/items` 내 알림(주기별 묶음), `/items/:id` 항목 상세(최근 걸린 공고·발송 상태), `/settings` 공통 조건
- `/admin/invites` 초대 코드, `/admin/runs` 실행 이력 — 관리자만
- 가입: 관리자가 만든 초대 코드 + 이메일 확인. 첫 관리자는 `ADMIN_EMAIL` 로 만들어지며 "비밀번호를 잊었어요" 로 첫 비밀번호를 정한다.
- 로그인 세션은 api 메모리에 있어 배포하면 모두 로그아웃된다.
- 로컬 개발: api 를 8080 으로 띄운 뒤 `cd admin && npm install && npm run dev` (Vite 가 `/api` 를 8080 으로 프록시)

## 운영 참고

- 서버는 두 프로세스다. `api`(화면 API·로그인, DB 접속 정보와 웹훅만 받음)와 `worker`(수집·발송·인증 메일, 서비스키·SMTP 를 받음, 포트 없음). 같은 Gradle 저장소의 `api`/`worker` 모듈이고 PostgreSQL 로만 연결된다.
- 인증 메일(가입 확인·비밀번호 재설정)은 api 가 `outbox_mail` 에 넣고 worker 가 10초마다 보낸다.
- 하루 보낸 메일이 400통을 넘으면 디스코드로 경고한다(Gmail 하루 한도 약 500통).
- 디스코드 알림(`DISCORD_WEBHOOK_URL`): 배치 연속 실패(메일과 같은 기준, 장애당 1회), 앱 ERROR 로그(같은 위치는 30분에 1회), 배포 실패(GitHub Secret `DISCORD_WEBHOOK_URL`)를 보낸다. 메시지의 `serviceKey` 는 가려진다.

- 개발계정 일일 호출 한도는 오퍼레이션별 1,000회다. 업무구분 하나당 하루 288회(5분 주기) + 페이지 수만큼 호출하므로, 공고가 많아 한도를 넘으면 활용사례 등록 후 운영계정 트래픽 증설을 신청한다.
- 입찰공고는 6회(30분), 사전규격은 1회 연속 실패하면 에러 알림 메일이 1회 발송된다. 복구되면 초기화된다.
- 서버가 멈췄다 재시작되면 마지막 성공 시각부터(최대 7일) 이어서 조회한다.
- `docker compose down -v` 는 발송 이력(pgdata 볼륨)을 지워 중복 메일이 발생하므로 쓰지 않는다.

## 배포 (Azure VM)

`master`에 push(PR 머지)하면 GitHub Actions `Deploy` 워크플로가 자동으로 배포한다.

1. 테스트(백엔드 + 어드민) → Docker 이미지 빌드 → `ghcr.io/wjdtn7823/nara-reminder-api:latest`, `ghcr.io/wjdtn7823/nara-reminder-worker:latest`, `ghcr.io/wjdtn7823/nara-reminder-web:latest` 푸시
2. `production` 환경(`master` 브랜치만 허용)에서 OIDC로 Azure에 로그인
3. `az vm run-command`로 VM에서 `deploy/remote-deploy.sh` 실행(`git pull` → `docker compose pull` → `up -d`)

- VM에는 인바운드 SSH(22)를 열지 않는다. 관리 명령도 `az vm run-command invoke` 또는 포털의 "명령 실행"으로 한다.
- VM 경로: `/opt/nara-reminder`. 비밀값은 VM의 `/opt/nara-reminder/.env`(root 전용, 권한 600)에만 있다.
- 알림 항목 변경: 화면에서 한다.
- 수동 재배포: Actions 탭에서 `Deploy` → "Run workflow" (`master`만 가능).
- 롤백: VM에서 `docker compose` 이미지 태그를 이전 커밋 SHA(`ghcr.io/wjdtn7823/nara-reminder-api:<sha>`, `ghcr.io/wjdtn7823/nara-reminder-worker:<sha>`, `ghcr.io/wjdtn7823/nara-reminder-web:<sha>`)로 지정해 실행한다.
- 어드민 도입(필터 DB 이관) 이전 버전으로 되돌릴 때는 이미지 태그만 바꾸면 안 된다. 구버전은 `config/application.yml` 필터 파일이 없으면 필터가 비어 **모든 공고를 메일로 보낸다**. VM 에서 `git -C /opt/nara-reminder checkout d06d7df` 로 필터 파일·compose 마운트를 복원한 뒤 `docker compose up -d app` 을 실행한다.
- 다중 사용자 전환(api/worker 분리) 이전으로 되돌리면 안 된다. 전환 뒤에는 `notified_notice` 가 갱신되지 않으므로 옛 버전은 전환 기간에 나온 공고를 **다시 메일로 보낸다**. 문제가 생기면 이전 커밋의 api/worker 이미지 태그로 되돌린다.
- 새 이미지 패키지(`nara-reminder-api`, `nara-reminder-worker`)는 처음 푸시되면 비공개일 수 있다. VM 은 로그인 없이 받으므로 GitHub 패키지 설정에서 Public 으로 바꿔야 배포된다.

## 개발

```bash
./gradlew test   # Docker 데몬 필요 (Testcontainers)
```
