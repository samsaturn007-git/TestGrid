#!/usr/bin/env bash
# PRD-08 T7: the gate's scripted examples against a running stack (`make up` first), each asserting
# the CLI's exit code (contracts §2.4). Runs locally (`make gate-scenarios`) and in the
# testgrid-gate workflow's `scenarios` job.
#
#   pass         PASS fixture                                   -> exit 0  (CI-001, AC-1)
#   fail         PRODUCT_DEFECT, critical cart-total fails      -> exit 1  (CI-002, AC-2)
#   infra        worker stopped, the CLI deadline passes        -> exit 2  (CI-003, AC-3)
#   bad-token    a wrong token / no token on a CI run           -> exit 3  (CI-004, AC-3)
#   bad-suite    a suite key the manifest doesn't have          -> exit 3  (AC-5: manifest only)
#   quarantined  cart-total quarantined: FAIL -> PASS with QUARANTINED_FAIL; once the quarantine
#                expires, FAIL again; both changes audited       (QUA-001, QUA-002, AC-4)
#
# Needs TESTGRID_TOKEN (the CI token the API was started with, TESTGRID_CI_TOKEN). Writes each
# scenario's JSON report and a markdown summary to $OUT (default tmp/gate-scenarios). Exit 0 only if
# every scenario behaved as expected.
set -uo pipefail
cd "$(dirname "$0")/.."

API=${TESTGRID_API_URL:-http://127.0.0.1:8080}
OUT=${OUT:-tmp/gate-scenarios}
SCENARIOS=${*:-pass fail bad-token bad-suite infra quarantined}
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
# A ci-ref per scenario run (contracts §4: "<system>:<id>"), unique to this invocation, so running
# the script again never replays an earlier run through the idempotency key.
if [ -n "${GITHUB_RUN_ID:-}" ]; then REF="gh:$GITHUB_RUN_ID/${GITHUB_RUN_ATTEMPT:-1}"; else REF="local:dev"; fi
ci_ref() { echo "$REF/$1-$(date +%s)-$RANDOM"; }
SUMMARY=${GITHUB_STEP_SUMMARY:-$OUT/summary.md}
# Long enough for one PRODUCT_DEFECT run to finish and be evaluated, short enough to wait out.
QUARANTINE_SECONDS=${QUARANTINE_SECONDS:-150}

if [ -z "${TESTGRID_TOKEN:-}" ]; then
  echo "gate-scenarios: set TESTGRID_TOKEN to the API's CI token (TESTGRID_CI_TOKEN)" >&2
  exit 2
fi
mkdir -p "$OUT"
results=()
failed=0

cli() { node cli/src/main.ts "$@" --api-url "$API"; }

# run_gate <name> <fixture> <timeout> -> writes $OUT/<name>.json, echoes the exit code
run_gate() {
  local name=$1 fixture=$2 timeout=$3
  cli run --project demo --suite checkout-smoke --engines chromium --fixture "$fixture" \
    --trigger CI --ci-ref "$(ci_ref "$name")" --wait --timeout "$timeout" \
    --json --summary-file "$SUMMARY" >"$OUT/$name.json" 2>"$OUT/$name.log"
  echo $?
}

check() { # check <scenario> <expected> <actual> <detail>
  local verdict=ok
  if [ "$2" != "$3" ]; then verdict=UNEXPECTED; failed=1; fi
  results+=("$(printf '%-28s expected %s  got %s  %-10s %s' "$1" "$2" "$3" "$verdict" "$4")")
  echo "gate-scenarios: $1: expected exit $2, got $3 ($verdict) $4"
}

json() { node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));const v=new Function("d","return "+process.argv[2])(d);console.log(typeof v==="string"?v:JSON.stringify(v))' "$1" "$2"; }

psql() { "${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-testgrid}" -d "${POSTGRES_DB:-testgrid}" -tAc "$1"; }

scenario_pass() {
  local code; code=$(run_gate pass PASS 10m)
  check "pass (CI-001)" 0 "$code" "outcome $(json "$OUT/pass.json" 'd.outcome')"
}

scenario_fail() {
  local code; code=$(run_gate fail PRODUCT_DEFECT 10m)
  check "fail (CI-002)" 1 "$code" \
    "failed: $(json "$OUT/fail.json" 'd.failedCases.map(c=>c.caseKey+"@"+c.engine+(c.critical?" critical":"")).join(", ")')"
}

scenario_bad_token() {
  local code
  TESTGRID_TOKEN=not-the-ci-token-0123456789abcdef0123 cli run --project demo \
    --suite checkout-smoke --engines chromium --trigger CI --ci-ref "gh:1/1" --json \
    >"$OUT/bad-token.json" 2>"$OUT/bad-token.log"
  code=$?
  check "bad token (CI-004)" 3 "$code" "$(json "$OUT/bad-token.json" 'd.error.status+" "+d.error.problemType')"
  env -u TESTGRID_TOKEN node cli/src/main.ts run --api-url "$API" --project demo \
    --suite checkout-smoke --engines chromium --trigger CI --ci-ref "gh:1/1" --json \
    >"$OUT/no-token.json" 2>"$OUT/no-token.log"
  code=$?
  check "no token on a CI run (CI-004)" 3 "$code" "$(json "$OUT/no-token.json" 'd.error.status+" "+d.error.problemType')"
}

scenario_bad_suite() {
  local code
  cli run --project demo --suite not-in-the-manifest --engines chromium --trigger CI \
    --ci-ref "$(ci_ref bad-suite)" --json >"$OUT/bad-suite.json" 2>"$OUT/bad-suite.log"
  code=$?
  check "suite outside the manifest" 3 "$code" "$(json "$OUT/bad-suite.json" 'd.error.status+" "+d.error.problemType')"
}

scenario_infra() {
  local code run_id
  "${COMPOSE[@]}" stop worker >/dev/null 2>&1
  code=$(run_gate infra PASS 45s)
  run_id=$(json "$OUT/infra.json" 'd.runId')
  "${COMPOSE[@]}" start worker >/dev/null 2>&1
  # The stuck run would otherwise start once the worker is back; it has served its purpose.
  if [ -n "$run_id" ]; then curl -fsS -X POST "$API/api/v1/runs/$run_id/cancel" >/dev/null || true; fi
  check "workers stopped (CI-003)" 2 "$code" \
    "deadlineReached $(json "$OUT/infra.json" 'd.deadlineReached'), run $(json "$OUT/infra.json" 'd.runStatus')"
}

scenario_quarantined() {
  local before code expires_at id
  before=$(psql "SELECT count(*) FROM audit_events WHERE action IN ('QUARANTINE_CREATED','QUARANTINE_EXPIRED')")
  # The expiry is set from the API's clock, which decides whether the quarantine is in force.
  expires_at=$(curl -fsS "$API/api/v1/projects/00000000-0000-4000-8000-000000000001/quarantines" |
    node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>console.log(new Date(Date.parse(JSON.parse(s).serverNow)+Number(process.argv[1])*1000).toISOString()))' "$QUARANTINE_SECONDS")
  id=$(curl -fsS -X POST -H 'Content-Type: application/json' \
    "$API/api/v1/projects/00000000-0000-4000-8000-000000000001/quarantines" \
    -d "{\"caseKey\":\"cart-total\",\"reason\":\"gate-scenarios: QUA-001 / QUA-002\",\"expiresAt\":\"$expires_at\"}" |
    node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>console.log(JSON.parse(s).id))')
  echo "gate-scenarios: quarantine $id on cart-total until $expires_at"
  code=$(run_gate quarantined PRODUCT_DEFECT 10m)
  check "quarantined (QUA-001)" 0 "$code" \
    "quarantinedFailures: $(json "$OUT/quarantined.json" 'd.quarantinedFailures.map(c=>c.caseKey+"@"+c.engine+" "+c.status).join(", ")')"
  # Wait out the quarantine on the API's clock, plus one sweep to record the expiry.
  while [ "$(psql "SELECT expires_at <= now() - interval '6 seconds' FROM quarantines WHERE id = '$id'")" != "t" ]; do
    sleep 5
  done
  code=$(run_gate expired PRODUCT_DEFECT 10m)
  check "quarantine expired (QUA-002)" 1 "$code" "outcome $(json "$OUT/expired.json" 'd.outcome')"
  local audits
  audits=$(psql "SELECT string_agg(action || ' by ' || actor, ', ' ORDER BY occurred_at) FROM audit_events WHERE object_id = '$id'")
  local added
  added=$(( $(psql "SELECT count(*) FROM audit_events WHERE action IN ('QUARANTINE_CREATED','QUARANTINE_EXPIRED')") - before ))
  check "both changes audited (AC-4)" 2 "$added" "$audits"
}

for s in $SCENARIOS; do
  case $s in
    pass) scenario_pass ;;
    fail) scenario_fail ;;
    bad-token) scenario_bad_token ;;
    bad-suite) scenario_bad_suite ;;
    infra) scenario_infra ;;
    quarantined) scenario_quarantined ;;
    *) echo "gate-scenarios: unknown scenario $s" >&2; exit 2 ;;
  esac
done

{
  echo
  echo "## Gate scenarios (PRD-08 T7)"
  echo
  echo '```text'
  printf '%s\n' "${results[@]}"
  echo '```'
} | tee -a "$SUMMARY" >"$OUT/results.txt"
cat "$OUT/results.txt"
if [ "$failed" -eq 0 ]; then echo "gate-scenarios OK"; else echo "gate-scenarios FAILED"; fi
exit "$failed"
