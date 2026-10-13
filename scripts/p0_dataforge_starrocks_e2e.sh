#!/usr/bin/env bash
# P0 read-only, synthetic-query reconciliation against the user-confirmed
# DataForge StarRocks test FE. Does not configure or change DataForge itself.
set -Eeuo pipefail

# The FE's internal address is preferred when insight-bi is in the same LAN.
# The public address previously provided for this test machine remains a
# configurable fallback; do not auto-switch between network endpoints.
export P0_STARROCKS_JDBC_URL="${P0_STARROCKS_JDBC_URL:-jdbc:mysql://172.17.0.12:9030/ods}"
export P0_STARROCKS_USER="${P0_STARROCKS_USER:-${DATAFORGE_STARROCKS_USER:-root}}"
export P0_STARROCKS_PASSWORD="${P0_STARROCKS_PASSWORD:-${DATAFORGE_STARROCKS_PASSWORD:-}}"

if [[ -z "${P0_STARROCKS_PASSWORD}" ]]; then
  echo "DataForge 测试 StarRocks 凭据缺失：请通过环境变量安全注入（不写入仓库）" >&2
  exit 2
fi
export P0_STARROCKS_E2E=true
export P0_STARROCKS_NONPROD_CONFIRMED=true
export P0_DATAFORGE_STARROCKS_TEST=true
app="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "$app/scripts/p0_starrocks_readonly_e2e.sh"
