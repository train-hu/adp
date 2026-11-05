#!/bin/bash
# 前端发布脚本（在 252 上执行）：
# 前置：build-web-on-252.sh 已在 /tmp/webbuild/dist 产出构建物
# 关键：替换 web-dist 后必须重建 web 容器 —— nginx 容器启动时 bind 的是目录
# inode，若 dist 目录被整体替换（rm+cp），容器仍挂载旧目录 inode，
# 导致线上一直发旧版前端（2026-11-05 踩坑）。
set -euo pipefail
cd /opt/autodeploy
STAMP=$(date +%Y%m%d-%H%M%S)
[ -d web-dist ] && mv web-dist "web-dist.old-$STAMP"
mkdir -p web-dist
cp -r /tmp/webbuild/dist/. web-dist/
# 只保留最近 2 个备份
ls -1d web-dist.old-* 2>/dev/null | head -n -2 | xargs -r rm -rf
docker-compose up -d --force-recreate web
# 校验：8081 返回的 index.html 必须与宿主机新 dist 一致
for i in $(seq 1 10); do
  A=$(curl -s http://127.0.0.1:8081/ | md5sum | awk '{print $1}')
  B=$(md5sum web-dist/index.html | awk '{print $1}')
  [ "$A" = "$B" ] && break
  sleep 2
done
[ "$A" = "$B" ] || { echo "WEB_DIST_MISMATCH: served=$A local=$B"; exit 1; }
echo "WEB_HTTP=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/)"
echo "WEB_DIST_MATCH=$A"
