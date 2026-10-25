#!/usr/bin/env bash
# GitHub Actions 가 Azure Run Command 로 VM 에서 실행하는 배포 스크립트 (root 로 실행됨)
set -euo pipefail

cd /opt/nara-reminder
git fetch -q origin master
git checkout -q master
git reset -q --hard origin/master

docker compose pull app
docker compose up -d --no-build
docker image prune -f > /dev/null

# 기동 직후 설정 오류로 재시작 루프에 빠지지 않았는지 확인한다
sleep 30
if [ "$(docker compose ps app --format '{{.State}}')" != "running" ]; then
  docker compose logs --tail 50 app
  exit 1
fi
git log --oneline -1
echo "DEPLOY_OK"
