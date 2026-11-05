#!/bin/bash
# 仅后端 jar 原子切换（前端已部署）
set -e
cd /opt/autodeploy
mv auto-deploy-server.jar auto-deploy-server.jar.bak-prev
mv auto-deploy-server.jar.new auto-deploy-server.jar
docker-compose up -d --force-recreate backend
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8081/api/ping >/dev/null 2>&1; then
    echo "BACKEND_OK after ${i}x3s"
    break
  fi
  sleep 3
done
curl -s -o /dev/null -w "WEB_HTTP=%{http_code}\n" http://127.0.0.1:8081/
docker ps --format '{{.Names}} {{.Status}}' | grep autodeploy
