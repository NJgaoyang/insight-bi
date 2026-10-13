#!/usr/bin/env bash
set -Eeuo pipefail
# Derive changed backend test classes from the Git diff; never execute full regression
# on unrelated docs/frontend changes. Maven still compiles changed backend sources.
base=""
if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" && -n "${GITHUB_BASE_REF:-}" ]]; then
  base="origin/${GITHUB_BASE_REF}"
else
  base="${GITHUB_EVENT_BEFORE:-}"
fi
if [[ -z "$base" || "$base" =~ ^0+$ ]] || ! git rev-parse -q --verify "${base}^{commit}" >/dev/null; then
  base="$(git rev-parse HEAD^ 2>/dev/null || git rev-parse HEAD)"
fi
mapfile -t paths < <(git diff --name-only "$base" HEAD)
backend=false frontend=false scripts=false
declare -A tests=()
add_test(){ tests["$1"]=1; }
for path in "${paths[@]}"; do
 case "$path" in
  backend/*)
   backend=true
   case "$path" in
    backend/src/test/java/*Test.java) name="${path##*/}";add_test "${name%.java}";;
    */DatabaseStore.java|*/BiApi.java)
      add_test FieldVisibilityTest;add_test DatasetAliasUpdateIntegrationTest;add_test BiQueryInTest;;
    */FieldCatalog.java)
      add_test FieldVisibilityTest;add_test FieldCatalogRefreshTest;add_test FieldCatalogConcurrencyTest;;
    */DatasetSchemaGuard.java)
      add_test DatasetSchemaGuardTest;add_test DatasetAliasUpdateIntegrationTest;;
    */AnalysisApi.java)
      add_test AnalysisFormulaValidationTest;add_test FieldDisplayNameTest;;
    */FormulaCompiler.java)
      add_test FormulaCompilerTypedTest;add_test FormulaComplexityTest;;
    */AnalysisQueryPlanner.java)
      add_test AnalysisQueryPlannerTest;add_test AnalysisFilterOperatorTest;;
    */ReadOnlySqlGuard.java) add_test ReadOnlySqlGuardTest;;
    */ReadOnlyConnectionPolicy.java) add_test ReadOnlyConnectionPolicyTest;;
    */AnalysisQueryLimiter.java|*/AnalysisErrorHandler.java) add_test AnalysisQueryLimiterTest;;
   esac;;
  frontend/*)frontend=true;;
  scripts/*)scripts=true;;
 esac
done
csv=""
if (("${#tests[@]}" > 0)); then
 csv="$(printf '%s\n' "${!tests[@]}" | sort | paste -sd, -)"
fi
{
 echo "backend=$backend"
 echo "frontend=$frontend"
 echo "scripts=$scripts"
 echo "tests=$csv"
 echo "base=$base"
} | tee -a "${GITHUB_OUTPUT:-/dev/null}"
