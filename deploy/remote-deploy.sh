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
