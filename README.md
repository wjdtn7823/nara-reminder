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
docker compose logs -f app
```

## 필터 규칙

필터는 어드민 화면(`https://<도메인>/filters`)에서 관리하며 저장 즉시 다음 수집부터 적용된다. DB(`filter_term`, `filter_setting`)에 저장된다.

- 키워드(공고명) **또는** 수요기관 중 하나만 맞아도 받는다. 둘 다 비어 있으면 전부 받는다.
- 위를 통과한 공고 중 제외 키워드가 공고명에 있으면 제외하고, 가격 범위를 벗어나면 제외한다.
- 문자열은 모두 부분일치(대소문자 무시). 띄어쓰기가 다르면 다른 단어로 본다(`유지관리` ≠ `유지 관리`).
- 업무구분: 물품(GOODS) 용역(SERVICE) 공사(CONSTRUCTION) 외자(FOREIGN)
- 최소/최대 가격: 입찰공고는 추정가격, 사전규격은 배정예산 기준(이상/이하). 가격이 없는 공고는 통과
- 수요기관: 입찰공고는 수요기관, 사전규격은 실수요기관 기준

## 어드민 화면

- `https://<SITE_ADDRESS>/filters` — 키워드·수요기관·제외 키워드 등록/수정/삭제, 업무구분·가격 범위
- `https://<SITE_ADDRESS>/runs` — 배치 실행 이력(90일 보관)
- 인증이 없다. 주소를 아는 누구나 필터를 바꿀 수 있으므로, 필요해지면 `web/Caddyfile` 에 `basic_auth` 를 추가한다.
- 로컬 개발: 앱을 8080 으로 띄운 뒤 `cd admin && npm install && npm run dev` (Vite 가 `/api` 를 8080 으로 프록시)

## 운영 참고

- 개발계정 일일 호출 한도는 오퍼레이션별 1,000회다. 업무구분 하나당 하루 288회(5분 주기) + 페이지 수만큼 호출하므로, 공고가 많아 한도를 넘으면 활용사례 등록 후 운영계정 트래픽 증설을 신청한다.
- 입찰공고는 6회(30분), 사전규격은 1회 연속 실패하면 에러 알림 메일이 1회 발송된다. 복구되면 초기화된다.
- 서버가 멈췄다 재시작되면 마지막 성공 시각부터(최대 7일) 이어서 조회한다.
- `docker compose down -v` 는 발송 이력(pgdata 볼륨)을 지워 중복 메일이 발생하므로 쓰지 않는다.

## 배포 (Azure VM)

`master`에 push(PR 머지)하면 GitHub Actions `Deploy` 워크플로가 자동으로 배포한다.

1. 테스트(백엔드 + 어드민) → Docker 이미지 빌드 → `ghcr.io/wjdtn7823/nara-reminder:latest`, `ghcr.io/wjdtn7823/nara-reminder-web:latest` 푸시
2. `production` 환경(`master` 브랜치만 허용)에서 OIDC로 Azure에 로그인
3. `az vm run-command`로 VM에서 `deploy/remote-deploy.sh` 실행(`git pull` → `docker compose pull` → `up -d`)

- VM에는 인바운드 SSH(22)를 열지 않는다. 관리 명령도 `az vm run-command invoke` 또는 포털의 "명령 실행"으로 한다.
- VM 경로: `/opt/nara-reminder`. 비밀값은 VM의 `/opt/nara-reminder/.env`(root 전용, 권한 600)에만 있다.
- 필터 변경: 어드민 화면에서 한다.
- 수동 재배포: Actions 탭에서 `Deploy` → "Run workflow" (`master`만 가능).
- 롤백: VM에서 `docker compose` 이미지 태그를 이전 커밋 SHA(`ghcr.io/wjdtn7823/nara-reminder:<sha>`, `ghcr.io/wjdtn7823/nara-reminder-web:<sha>`)로 지정해 실행한다.
- 어드민 도입(필터 DB 이관) 이전 버전으로 되돌릴 때는 이미지 태그만 바꾸면 안 된다. 구버전은 `config/application.yml` 필터 파일이 없으면 필터가 비어 **모든 공고를 메일로 보낸다**. VM 에서 `git -C /opt/nara-reminder checkout d06d7df` 로 필터 파일·compose 마운트를 복원한 뒤 `docker compose up -d app` 을 실행한다.

## 개발

```bash
./gradlew test   # Docker 데몬 필요 (Testcontainers)
```
