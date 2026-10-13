#!/usr/bin/env bash
set -Eeuo pipefail
APP=/opt/insight-bi
JAR="$APP/backend/target/insight-bi-0.1.0.jar"
BACKUP="$APP/runtime/previous-insight-bi.jar"

echo "[1/5] TypeScript check and frontend production build"
(cd "$APP/frontend" && npm run build)

echo "[2/5] Refresh generated static assets"
rm -rf "$APP/backend/src/main/resources/static"
mkdir -p "$APP/backend/src/main/resources/static"
cp -r "$APP/frontend/dist/." "$APP/backend/src/main/resources/static/"

echo "[3/5] Stop old service before jar replacement"
if [ -f "$JAR" ]; then cp "$JAR" "$BACKUP"; fi
systemctl stop insight-bi

echo "[4/5] Package Spring Boot"
if ! (cd "$APP/backend" && mvn -q -DskipTests package); then
 echo "Backend build failed; restoring previous service"
 if [ -f "$BACKUP" ]; then cp "$BACKUP" "$JAR"; fi
 systemctl start insight-bi
 exit 1
fi

echo "[5/5] Start and verify"
systemctl start insight-bi
for i in $(seq 1 18); do
 if curl -fsS --max-time 2 http://127.0.0.1:18110/api/health | grep -q '"ok":true'; then
  echo "Insight BI is healthy."
  exit 0
 fi
 sleep 1
done
echo "Healthcheck failed, inspect journalctl -u insight-bi"
exit 1
