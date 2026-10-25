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

- `keywords`(공고명) **또는** `agencies`(수요기관) 중 하나만 맞아도 받는다. 둘 다 비어 있으면 전부 받는다.
- 위를 통과한 공고 중 `exclude-keywords`가 공고명에 있으면 제외하고, 가격 범위를 벗어나면 제외한다.
- 문자열은 모두 부분일치(대소문자 무시). 띄어쓰기가 다르면 다른 단어로 본다(`유지관리` ≠ `유지 관리`).
- `categories`: `GOODS`(물품) `SERVICE`(용역) `CONSTRUCTION`(공사) `FOREIGN`(외자)
- `price-min` / `price-max`: 입찰공고는 추정가격, 사전규격은 배정예산 기준(이상/이하). 가격이 없는 공고는 통과
- `agencies`: 입찰공고는 수요기관, 사전규격은 실수요기관 기준

## 운영 참고

- 개발계정 일일 호출 한도는 오퍼레이션별 1,000회다. 업무구분 하나당 하루 288회(5분 주기) + 페이지 수만큼 호출하므로, 공고가 많아 한도를 넘으면 활용사례 등록 후 운영계정 트래픽 증설을 신청한다.
- 입찰공고는 6회(30분), 사전규격은 1회 연속 실패하면 에러 알림 메일이 1회 발송된다. 복구되면 초기화된다.
- 서버가 멈췄다 재시작되면 마지막 성공 시각부터(최대 7일) 이어서 조회한다.
- `docker compose down -v` 는 발송 이력(pgdata 볼륨)을 지워 중복 메일이 발생하므로 쓰지 않는다.

## 개발

```bash
./gradlew test   # Docker 데몬 필요 (Testcontainers)
```
