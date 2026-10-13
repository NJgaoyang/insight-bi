#!/usr/bin/env bash
set -Eeuo pipefail

# Explicit opt-in only: never load production passwords or create test tables.
if [[ "${P0_STARROCKS_E2E:-}" != "true" || "${P0_STARROCKS_NONPROD_CONFIRMED:-}" != "true" ]]; then
  echo "拒绝执行：请先确认独立非生产 StarRocks，并设置两个 opt-in 环境变量为 true" >&2
  exit 2
fi
for required in P0_STARROCKS_JDBC_URL P0_STARROCKS_USER P0_STARROCKS_PASSWORD; do
  if [[ -z "${!required:-}" ]]; then
    echo "拒绝执行：缺少环境变量 ${required}" >&2
    exit 2
  fi
done
case "${P0_STARROCKS_JDBC_URL}" in
  jdbc:mysql://*) ;;
  *) echo "拒绝执行：JDBC URL 必须为 StarRocks 的 MySQL 协议地址" >&2; exit 2;;
esac
cd "$(dirname "${BASH_SOURCE[0]}")/../backend"
mvn -B -q -Dtest=StarRocksReadOnlyReconciliationTest test
echo "只读 StarRocks 对账测试已结束；请确认 Maven 报告与预期值"
