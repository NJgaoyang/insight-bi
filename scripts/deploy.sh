#!/usr/bin/env bash
set -Eeuo pipefail
APP=/opt/insight-bi
JAR="$APP/backend/target/insight-bi-0.1.0.jar"
BACKUP="$APP/runtime/previous-insight-bi.jar"
REQUIRED_ENV="$APP/runtime/local.env"

# Do not make the live Java process unrestartable during a deploy.
# In particular, a missing runtime/local.env must be diagnosed BEFORE stop.
if [[ ! -s "$REQUIRED_ENV" ]]; then
 echo "Refusing deploy: runtime/local.env is missing or empty; recover server-only configuration first." >&2
 exit 2
fi
if [[ ! -s "$JAR" ]]; then
 echo "Refusing deploy: existing runnable JAR is missing; recover its binary before restarting." >&2
 exit 2
fi
if ! systemctl is-active --quiet insight-bi; then
 echo "Refusing deploy: existing insight-bi service is not healthy/active; investigate first." >&2
 exit 2
fi
install -d -m 700 "$APP/runtime"

restore_previous(){
 echo "Restoring previous Insight BI JAR and attempting service recovery" >&2
 systemctl stop insight-bi || true
 if [[ -s "$BACKUP" ]]; then
  install -m 644 "$BACKUP" "$JAR"
  systemctl start insight-bi || echo "Rollback start failed; manual recovery required" >&2
 fi
}

echo "[1/5] TypeScript check and frontend production build"
(cd "$APP/frontend" && npm run build)

echo "[2/5] Refresh generated static assets"
rm -rf "$APP/backend/src/main/resources/static"
mkdir -p "$APP/backend/src/main/resources/static"
cp -r "$APP/frontend/dist/." "$APP/backend/src/main/resources/static/"

echo "[3/5] Securely back up current runnable JAR (before stopping)"
cp "$JAR" "$BACKUP"
chmod 600 "$BACKUP"

echo "[4/5] Stop service and package Spring Boot"
systemctl stop insight-bi
if ! (cd "$APP/backend" && mvn -q -DskipTests package); then
 echo "Backend build failed" >&2
 restore_previous
 exit 1
fi

echo "[5/5] Start and verify"
if ! systemctl start insight-bi; then
 restore_previous
 exit 1
fi
for i in $(seq 1 18); do
 if curl -fsS --max-time 2 http://127.0.0.1:18110/api/health | grep -q '"ok":true'; then
  echo "Insight BI is healthy."
  exit 0
 fi
 sleep 1
done
echo "Healthcheck failed; rolling back runnable JAR" >&2
restore_previous
exit 1
