#!/usr/bin/env bash
set -Eeuo pipefail
set +x

# Safe, read-only acceptance against the user-designated existing FE.
# Credentials are intentionally never stored in this script or GitHub.
HOST='81.69.15.136'
PORT='9030'

if [[ -z "${BI_ACCEPTANCE_STARROCKS_USERNAME:-}" ]]; then
  BI_ACCEPTANCE_STARROCKS_USERNAME='root'
fi
if [[ -z "${BI_ACCEPTANCE_STARROCKS_PASSWORD:-}" ]]; then
  read -rsp 'StarRocks password (input hidden): ' BI_ACCEPTANCE_STARROCKS_PASSWORD
  printf '\n'
fi
if [[ -z "$BI_ACCEPTANCE_STARROCKS_PASSWORD" ]]; then
  echo 'StarRocks password missing' >&2
  exit 2
fi

echo "Checking TCP connectivity to ${HOST}:${PORT} (no SQL or credentials sent)..."
if ! timeout 5 bash -c 'cat < /dev/null > /dev/tcp/"$1"/"$2"' _ "$HOST" "$PORT" 2>/dev/null; then
  echo 'FE port not reachable from this machine. Check routing, FE bind address, firewall and port 9030.' >&2
  exit 3
fi

export BI_ACCEPTANCE_STARROCKS_USERNAME
export BI_ACCEPTANCE_STARROCKS_PASSWORD
export BI_ACCEPTANCE_EXISTING_STARROCKS='yes'

cd "$(dirname "$0")/../backend"
# Both tests in this class run; one checks SQL compiler locally and
# the other checks V2 queries on the FE with SELECT-only virtual data.
mvn -B -q '-Dtest=StarRocksExistingInstanceReadOnlyTest' test
echo 'StarRocks existing-instance SELECT-only V2 acceptance completed.'
