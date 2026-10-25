#!/usr/bin/env bash
# GitHub Actions 가 Azure Run Command 로 VM 에서 실행하는 배포 스크립트 (root 로 실행됨)
set -euo pipefail

cd /opt/nara-reminder
git fetch -q origin master
git checkout -q master
git reset -q --hard origin/master

# 호스트에 직접 설치했던 Caddy 는 web 컨테이너로 대체한다 (80/443 충돌 방지, 1회성)
if systemctl list-unit-files caddy.service > /dev/null 2>&1 && systemctl is-enabled --quiet caddy 2>/dev/null; then
  systemctl disable --now caddy
  DEBIAN_FRONTEND=noninteractive apt-get remove -y -qq caddy > /dev/null
fi

docker compose pull app web
docker compose up -d --no-build
docker image prune -f > /dev/null

# 기동 직후 설정 오류로 재시작 루프에 빠지지 않았는지 확인한다
sleep 30
for service in app web; do
  if [ "$(docker compose ps "$service" --format '{{.State}}')" != "running" ]; then
    docker compose logs --tail 50 "$service"
    exit 1
  fi
done
git log --oneline -1
echo "DEPLOY_OK"
