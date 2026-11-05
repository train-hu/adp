#!/bin/bash
# 252 密钥轮换：备份泄露的 compose，生成 .env（随机强密钥），重建 backend
set -e
cd /opt/autodeploy
STAMP=$(date +%Y%m%d-%H%M%S)

# 1. 备份当前（含泄露密钥的）compose
cp docker-compose.yml "docker-compose.yml.bak-leak-${STAMP}"

# 2. 生成随机强密钥并写入 .env（600 权限）
umask 077
cat > .env <<EOF
DB_PASSWORD=deploy123
DEPLOY_JWT_SECRET=rotated-${STAMP}-$(openssl rand -hex 32)
CREDENTIAL_KEK=rotated-${STAMP}-$(openssl rand -hex 32)
DEPLOY_SEED_PASSWORD=admin123
EOF
chmod 600 .env
echo "ENV_KEYS:"; cut -d= -f1 .env
echo "JWT_LEN=$(awk -F= '/DEPLOY_JWT_SECRET/{print length($2)}' .env)"
echo "KEK_LEN=$(awk -F= '/CREDENTIAL_KEK/{print length($2)}' .env)"

# 3. 用新 compose（环境变量注入版）重建 backend
if [ -f docker-compose.yml.new ]; then
  mv docker-compose.yml.new docker-compose.yml
fi
COMPOSE=docker-compose
$COMPOSE up -d --force-recreate backend

# 4. 健康检查
OK=0
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8081/api/ping >/dev/null 2>&1; then
    echo "BACKEND_OK after ${i}x3s"; OK=1; break
  fi
  sleep 3
done
[ "$OK" = 1 ] || { echo "BACKEND_FAIL"; docker-compose logs --tail 30 backend; exit 1; }
curl -s -o /dev/null -w "WEB_HTTP=%{http_code}\n" http://127.0.0.1:8081/
docker ps --format '{{.Names}} {{.Status}}' | grep autodeploy
