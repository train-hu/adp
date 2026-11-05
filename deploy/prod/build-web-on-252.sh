#!/bin/bash
set -euo pipefail
WORK=/tmp/webbuild
rm -rf "$WORK"
mkdir -p "$WORK"
tar -xzf /tmp/web-src.tar.gz -C "$WORK"
echo "BUILD_START $(date -u +%FT%TZ)"
docker run --rm \
  -v "$WORK:/app" -w /app \
  node:22-alpine sh -c "npm ci --no-audit --no-fund && npm run build"
echo "BUILD_DONE"
ls -la "$WORK/dist" | head
echo "DIST_INDEX_HASH=$(md5sum "$WORK/dist/index.html" | awk '{print $1}')"
