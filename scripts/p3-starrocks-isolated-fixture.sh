#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

# Existing authorized FE, but fixture objects are random & disposable.
HOST='81.69.15.136'
PORT=9030
ADMIN_FILE="${BI_STARROCKS_ADMIN_DEFAULTS_FILE:-}"
if [[ -z "$ADMIN_FILE" || ! -f "$ADMIN_FILE" ]]; then
  echo "Set BI_STARROCKS_ADMIN_DEFAULTS_FILE to a chmod 600 MySQL options file" >&2
  exit 2
fi
if [[ "$(stat -c %a "$ADMIN_FILE")" != "600" ]]; then
  echo "StarRocks admin options file must have chmod 600" >&2
  exit 2
fi
base="$(cd "$(dirname "$0")/.." && pwd)"
suffix="$(openssl rand -hex 8)"
user_suffix="$(openssl rand -hex 6)"
schema="insight_ci_${suffix}"
reader="bi_ci_${user_suffix}"
password="$(openssl rand -hex 22)"
password_file="$(mktemp /tmp/insight-starrocks-ci-reader.XXXXXXXX)"
printf %s "$password" > "$password_file"
chmod 600 "$password_file"
created_db=0
created_user=0

admin_sql(){
  mysql --defaults-extra-file="$ADMIN_FILE" --host="$HOST" --port="$PORT" \
    --protocol=tcp --connect-timeout=5 --batch --skip-column-names
}
cleanup(){
  local rc=$?
  trap - EXIT
  if (( created_user )); then
    printf "DROP USER IF EXISTS '%s'@'%%';\n" "$reader" | admin_sql >/dev/null 2>&1 \
      || echo 'Warning: test user cleanup failed; manual review required' >&2
  fi
  if (( created_db )); then
    printf 'DROP DATABASE IF EXISTS %s;\n' "$schema" | admin_sql >/dev/null 2>&1 \
      || echo 'Warning: test database cleanup failed; manual review required' >&2
  fi
  shred -u "$password_file" 2>/dev/null || rm -f "$password_file"
  exit "$rc"
}
trap cleanup EXIT

echo "Preparing ephemeral StarRocks schema ${schema} on ${HOST}:${PORT}"
# CREATE without IF NOT EXISTS avoids silently taking over an existing schema.
printf 'CREATE DATABASE %s;\n' "$schema" | admin_sql >/dev/null
created_db=1
printf 'CREATE TABLE %s.insight_ci_fact (city VARCHAR(32) NOT NULL,revenue DECIMAL(12,2) NOT NULL) ENGINE=OLAP DUPLICATE KEY(city) DISTRIBUTED BY HASH(city) BUCKETS 1 PROPERTIES ("replication_num"="1");\n' "$schema" | admin_sql >/dev/null
printf "INSERT INTO %s.insight_ci_fact VALUES ('北京',100),('北京',50),('上海',200),('广州',300);\n" "$schema" | admin_sql >/dev/null
# Credentials are piped on stdin, not placed in SQL CLI argv or GitHub.
printf "CREATE USER '%s'@'%%' IDENTIFIED BY '%s';\n" "$reader" "$password" | admin_sql >/dev/null
created_user=1
printf "GRANT SELECT ON TABLE %s.insight_ci_fact TO USER '%s'@'%%';\n" "$schema" "$reader" | admin_sql >/dev/null

echo "Running real V2 and server-side privilege checks with ephemeral reader"
cd "$base/backend"
BI_ACCEPTANCE_SCOPED_STARROCKS=yes \
BI_ACCEPTANCE_STARROCKS_HOST="$HOST" \
BI_ACCEPTANCE_STARROCKS_SCHEMA="$schema" \
BI_ACCEPTANCE_STARROCKS_READER="$reader" \
BI_ACCEPTANCE_STARROCKS_READER_PASSWORD_FILE="$password_file" \
  mvn -B -q '-Dtest=StarRocksScopedAccessTest' test

echo 'StarRocks real OLAP V2 aggregate + unauthorized DML/DDL checks passed.'
echo 'Cleaning up temporary StarRocks schema and user (via EXIT trap).'
