#!/usr/bin/env bash
# Entirely synthetic non-production release-gate test; never uses /opt/insight-bi.
set -Eeuo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
h2jar="${P0_H2_JAR:-}"
[[ -s "$h2jar" ]] || { echo "Set P0_H2_JAR to a local H2 jar" >&2; exit 2; }
tmp="$(mktemp -d /tmp/p0-release-gate.XXXXXX)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/runtime" "$tmp/scripts" "$tmp/backend/target"
cp "$here/P0MetadataRestoreVerify.java" "$tmp/scripts/"
printf 'FAKE TEST CONFIG (not valid production credentials)\n' > "$tmp/runtime/local.env"
printf 'synthetic JAR marker\n' > "$tmp/backend/target/insight-bi-0.1.0.jar"

cat > "$tmp/create.sql" <<'SQL'
CREATE TABLE insight_dataset(id VARCHAR(36));
CREATE TABLE insight_dashboard(id VARCHAR(36));
CREATE TABLE insight_dashboard_version(id VARCHAR(36));
CREATE TABLE insight_dataset_field(id VARCHAR(36));
CREATE TABLE insight_calculated_field(id VARCHAR(36));
INSERT INTO insight_dataset VALUES('fixture');
SQL
java -cp "$h2jar" org.h2.tools.RunScript -url "jdbc:h2:file:$tmp/source;MODE=MySQL" \
  -user sa -script "$tmp/create.sql"
printf "BACKUP TO '%s/runtime/p0-metadata-consistent.zip';\n" "$tmp" > "$tmp/backup.sql"
java -cp "$h2jar" org.h2.tools.RunScript -url "jdbc:h2:file:$tmp/source;MODE=MySQL" \
  -user sa -script "$tmp/backup.sql"

P0_H2_JAR="$h2jar" P0_APP_ROOT="$tmp" bash "$here/p0_verify_h2_restore.sh"
test -s "$tmp/runtime/p0-h2-restore.sha256"
cat > "$tmp/runtime/p0-starrocks-surefire.xml" <<'XML'
<testsuite tests="1" errors="0" failures="0" skipped="0">
  <testcase classname="com.insightbi.analysis.StarRocksReadOnlyReconciliationTest" name="syntheticOnly"/>
</testsuite>
XML

# This XML is a disposable fixture only. It proves the gate's parsing, not
# that any live StarRocks test actually ran.
P0_APP_ROOT="$tmp" P0_PREFLIGHT_TEST_MODE=true bash "$here/p0_release_preflight.sh"
sed -i 's/skipped="0"/skipped="1"/' "$tmp/runtime/p0-starrocks-surefire.xml"
if P0_APP_ROOT="$tmp" P0_PREFLIGHT_TEST_MODE=true bash "$here/p0_release_preflight.sh" >/dev/null 2>&1; then
  echo "FAIL: skipped StarRocks test was accepted" >&2; exit 1
fi
sed -i 's/skipped="1"/skipped="0"/' "$tmp/runtime/p0-starrocks-surefire.xml"
printf 'tampered' >> "$tmp/runtime/p0-metadata-consistent.zip"
if P0_APP_ROOT="$tmp" P0_PREFLIGHT_TEST_MODE=true bash "$here/p0_release_preflight.sh" >/dev/null 2>&1; then
  echo "FAIL: tampered H2 backup was accepted" >&2; exit 1
fi
echo "PASS: isolated recovery generates evidence; skipped E2E and tampered backup are rejected"
