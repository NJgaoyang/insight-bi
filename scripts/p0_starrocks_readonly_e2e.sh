#!/usr/bin/env bash
# Strictly opt-in, non-production, read-only StarRocks P0 reconciliation.
set -Eeuo pipefail
if [[ "${P0_STARROCKS_E2E:-}" != "true" ||
      "${P0_STARROCKS_NONPROD_CONFIRMED:-}" != "true" ]]; then
  echo "拒绝执行：必须明确确认独立非生产 StarRocks 和 E2E 开关" >&2
  exit 2
fi
for key in P0_STARROCKS_JDBC_URL P0_STARROCKS_USER P0_STARROCKS_PASSWORD; do
  [[ -n "${!key:-}" ]] || { echo "拒绝执行：缺少连接参数 $key" >&2; exit 2; }
done
case "${P0_STARROCKS_JDBC_URL}" in
  jdbc:mysql://*) ;;
  *) echo "拒绝执行：仅支持 StarRocks 的 MySQL JDBC URL" >&2; exit 2;;
esac
case "${P0_STARROCKS_USER,,}" in
  root|admin|administrator)
    echo "拒绝执行：必须使用专门创建的非生产只读数据库账号，不可用管理员" >&2
    exit 2;;
esac

app="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report="$app/backend/target/surefire-reports/TEST-com.insightbi.analysis.StarRocksReadOnlyReconciliationTest.xml"
(cd "$app/backend" && mvn -B -q -Dtest=StarRocksReadOnlyReconciliationTest test)

# Surefire can return success even when every JUnit test is @Disabled. Do not
# issue a release receipt for a skipped test, missing XML or stale results.
python3 - "$report" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
xml=pathlib.Path(sys.argv[1])
if not xml.is_file():
    raise SystemExit("StarRocks E2E report missing")
root=ET.parse(xml).getroot()
if int(root.get("tests","0"))<1 or any(int(root.get(k,"0"))!=0
    for k in ("failures","errors","skipped")):
    raise SystemExit("StarRocks E2E was skipped or failed")
print("StarRocks non-production read-only E2E: actual JUnit cases passed")
PY
install -d -m 700 "$app/runtime"
install -m 600 "$report" "$app/runtime/p0-starrocks-surefire.xml"
echo "Non-skipped StarRocks E2E report saved in private runtime"
