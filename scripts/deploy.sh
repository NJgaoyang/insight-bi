#!/usr/bin/env bash
# Explicitly approved P0 release only. Build in staging before stopping service.
set -Eeuo pipefail
APP=/opt/insight-bi
JAR="$APP/backend/target/insight-bi-0.1.0.jar"
BACKUP="$APP/runtime/previous-insight-bi.jar"
REQUIRED_ENV="$APP/runtime/local.env"

if [[ "${P0_APPROVE_DEPLOY:-}" != "true" ]]; then
  echo "Refusing deploy: set P0_APPROVE_DEPLOY=true only after release review." >&2
  exit 2
fi
# This step is read-only. It verifies that a restorable metadata backup and a
# real, non-skipped StarRocks integration report have been supplied.
if ! bash "$APP/scripts/p0_release_preflight.sh"; then
  echo "Refusing deploy: P0 release evidence gate has not passed." >&2
  exit 2
fi
[[ -s "$REQUIRED_ENV" ]] || { echo "Missing runtime/local.env" >&2; exit 2; }
[[ -s "$JAR" ]] || { echo "Missing original runnable JAR" >&2; exit 2; }
systemctl is-active --quiet insight-bi || {
  echo "Existing service is not active; refusing deployment" >&2
  exit 2
}

umask 077
install -d -m 700 "$APP/runtime"
stage="$(mktemp -d "$APP/runtime/.p0-build.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
mkdir -p "$stage/backend"

echo "[1/5] Compile frontend while production is running"
(cd "$APP/frontend" && npm run build)

echo "[2/5] Copy backend source to isolated staging directory"
cp "$APP/backend/pom.xml" "$stage/backend/pom.xml"
cp -a "$APP/backend/src" "$stage/backend/src"
# Never modify the active backend source's embedded static resources mid-release.
rm -rf "$stage/backend/src/main/resources/static"
mkdir -p "$stage/backend/src/main/resources/static"
cp -a "$APP/frontend/dist/." "$stage/backend/src/main/resources/static/"

echo "[3/5] Package new JAR in staging (no production changes yet)"
(cd "$stage/backend" && mvn -B -q -DskipTests package)
NEXT_JAR="$stage/backend/target/insight-bi-0.1.0.jar"
[[ -s "$NEXT_JAR" ]] || { echo "Staged JAR missing" >&2; exit 1; }

echo "[4/5] Preserve old runnable JAR and replace only after stopping"
install -m 600 "$JAR" "$BACKUP"
if ! systemctl stop insight-bi; then
  echo "Stop failed: production JAR was not replaced" >&2
  exit 1
fi
restore_previous(){
  echo "Restoring previously running Insight BI JAR" >&2
  systemctl stop insight-bi || true
  if [[ -s "$BACKUP" ]]; then
    install -m 644 "$BACKUP" "$JAR"
    systemctl start insight-bi || echo "Rollback start failed; manual recovery required" >&2
  fi
}
if ! install -m 644 "$NEXT_JAR" "$JAR.p0-next"; then
  restore_previous
  exit 1
fi
if ! mv -f "$JAR.p0-next" "$JAR"; then
  restore_previous
  exit 1
fi

echo "[5/5] Start and check health, rollback on failure"
if ! systemctl start insight-bi; then
  restore_previous
  exit 1
fi
for i in $(seq 1 18); do
  if curl -fsS --max-time 2 http://127.0.0.1:18110/api/health |
      grep -q '"ok":true'; then
    echo "P0 release healthy"
    exit 0
  fi
  sleep 1
done
echo "Post-release healthcheck failed" >&2
restore_previous
exit 1
