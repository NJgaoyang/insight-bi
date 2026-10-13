#!/usr/bin/env bash
# Read-only P0 release gate. Does not start/stop any service or modify metadata.
set -Eeuo pipefail
app="${P0_APP_ROOT:-/opt/insight-bi}"
runtime="$app/runtime"
env_file="$runtime/local.env"
jar="$app/backend/target/insight-bi-0.1.0.jar"
backup="$runtime/p0-metadata-consistent.zip"
receipt="$runtime/p0-h2-restore.sha256"
starrocks_report="$runtime/p0-starrocks-surefire.xml"

fail(){
  printf 'P0 release blocked: %s\n' "$1" >&2
  exit 2
}
[[ -s "$env_file" ]] || fail "服务器专用 runtime/local.env 不存在"
[[ -s "$jar" ]] || fail "原服务 JAR 不存在，无法保留可回滚版本"
[[ -s "$backup" ]] || fail "缺少通过一致性备份流程生成的 H2 ZIP"
[[ -s "$receipt" ]] || fail "缺少独立恢复验证 SHA-256 凭据"
[[ -s "$starrocks_report" ]] || fail "缺少真实非生产 StarRocks Surefire 结果"
# Restricted non-production test mode may only bypass service checks when
# pointing to an isolated directory, never the standard production path.
if [[ "$app" == "/opt/insight-bi" ]]; then
  systemctl is-active --quiet insight-bi || fail "生产服务不是 active"
  curl -fsS --max-time 3 http://127.0.0.1:18110/api/health |
    grep -q '"ok":true' || fail "生产健康检查未通过"
elif [[ "${P0_PREFLIGHT_TEST_MODE:-}" != "true" ]]; then
  fail "自定义 APP_ROOT 仅允许在独立 preflight 测试中使用"
fi

python3 - "$runtime" "$backup" "$receipt" "$starrocks_report" <<'PY'
import hashlib, pathlib, sys, xml.etree.ElementTree as ET, zipfile
runtime, backup, receipt, report = map(lambda x: pathlib.Path(x).resolve(), sys.argv[1:])
for p in (backup,receipt,report):
    if not p.is_relative_to(runtime):
        raise SystemExit("P0 release blocked: 备份及验收证据必须位于私有 runtime")
expected=receipt.read_text(encoding="utf-8").strip().lower()
if len(expected)!=64 or any(c not in "0123456789abcdef" for c in expected):
    raise SystemExit("P0 release blocked: 无效的备份恢复 SHA-256 凭据")
actual=hashlib.file_digest(backup.open("rb"),"sha256").hexdigest()
if expected!=actual:
    raise SystemExit("P0 release blocked: 恢复验证 SHA-256 与备份内容不匹配")
try:
    with zipfile.ZipFile(backup) as z:
        bad=z.testzip()
        if bad or not any(info.filename.endswith(".mv.db") for info in z.infolist()):
            raise ValueError("H2 备份不完整")
except Exception:
    raise SystemExit("P0 release blocked: H2 备份 ZIP 校验不通过")
try:
    result=ET.parse(report).getroot()
    if result.tag=="testsuites":
        suites=list(result.findall("testsuite"))
    elif result.tag=="testsuite":
        suites=[result]
    else:
        raise ValueError("unknown report")
    if not suites or sum(int(x.get("tests","0")) for x in suites)<1:
        raise ValueError("empty report")
    for suite in suites:
        if any(int(suite.get(k,"0"))!=0 for k in ("errors","failures","skipped")):
            raise ValueError("StarRocks run was skipped or failed")
    names=" ".join((tc.get("classname","")+" "+tc.get("name","")) for suite in suites for tc in suite.findall("testcase"))
    if "StarRocksReadOnlyReconciliationTest" not in names:
        raise ValueError("not a real StarRocks reconciliation report")
except Exception:
    raise SystemExit("P0 release blocked: 真数对账报告不完整、跳过或失败")
print("P0 evidence: H2 backup SHA-256 + ZIP integrity verified, non-skipped StarRocks test passed")
PY
echo "P0 release preflight passed (no production changes made)"
