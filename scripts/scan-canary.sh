#!/usr/bin/env bash
# PRD-06 AC-4 (SEC-002 seed): put a fresh canary secret into the worker's and API's environment and
# into every test page (cookie, DOM and test output; worker/suites/support/shop-test.ts), run a
# PRODUCT_DEFECT run, then scan everything the run stored or served for the canary: API responses,
# the event stream, the database rows, every stored artifact (traces unzipped) and the api and
# worker logs. One hit fails the drill.
#
# Positive controls, so a pass means something: the stored log tail must show the masked canary
# line, and the failed case's trace (which holds the canary cookie) must have been withheld
# (CONFLICT_REGISTER N22). Screenshots are pixels and are not text-scanned (a known limitation).
#
# The canary is 34 characters, below the 40-character "long opaque string" rule, so only the
# secret-value rule can catch it. Afterwards api and worker are recreated without the canary.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
API=${API_URL:-http://127.0.0.1:8080}
PROJECT=00000000-0000-4000-8000-000000000001

grep -qx 'TESTGRID_PROFILE=local' .env || { echo "scan-canary needs TESTGRID_PROFILE=local in .env"; exit 2; }
CANARY="tg-canary-$(openssl rand -hex 12)"
work=$(mktemp -d "${TMPDIR:-/tmp}/tg-canary-scan.XXXXXX")

restore() {
  echo "--- recreating api and worker without the canary"
  TESTGRID_CANARY_SECRET='' "${COMPOSE[@]}" up -d --wait api worker >/dev/null 2>&1 || echo "restore failed: run make up"
  rm -rf "$work"
}
trap restore EXIT

sql() {
  "${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 -c "$1"' sh "$1"
}

echo "--- starting api and worker with a fresh canary (value not printed)"
TESTGRID_CANARY_SECRET="$CANARY" "${COMPOSE[@]}" up -d --build --wait --wait-timeout 180 >"$work/up.log" 2>&1 \
  || { cat "$work/up.log"; exit 1; }
rm "$work/up.log"

body=$(printf '{"suiteKey":"checkout-smoke","targets":[{"executor":"LOCAL","engine":"chromium"}],"trigger":"MANUAL","idempotencyKey":"scan-canary-%s","fixture":{"mode":"PRODUCT_DEFECT","seed":42}}' "$(date +%s)")
run=$(curl -fsS -X POST -H 'content-type: application/json' -d "$body" "$API/api/v1/projects/$PROJECT/runs" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "created run $run (PRODUCT_DEFECT on chromium)"
status=""
for _ in $(seq 1 240); do
  status=$(curl -fsS "$API/api/v1/runs/$run" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  case "$status" in PASSED|FAILED|TIMED_OUT|INFRA_ERROR|CANCELLED) break ;; esac
  sleep 1
done
echo "run finished: $status"

echo "--- collecting what the run stored and served"
mkdir -p "$work/api" "$work/db" "$work/unzipped"
curl -fsS "$API/api/v1/runs/$run" >"$work/api/run.json"
curl -fsS "$API/api/v1/runs/$run/results" >"$work/api/results.json"
curl -fsS "$API/api/v1/runs?limit=100" >"$work/api/runs.json"
curl -fsS "$API/api/v1/workers" >"$work/api/workers.json"
# Last-Event-ID: 0 replays every event of the run, then the stream is cut after 3 s.
curl -sS -N --max-time 3 -H 'Last-Event-ID: 0' "$API/api/v1/runs/$run/events" >"$work/api/events.txt" 2>/dev/null || true
for table in runs run_targets attempts case_results run_events artifacts workers; do
  sql "SELECT row_to_json(t)::text FROM $table t" >"$work/db/$table.jsonl"
done
"${COMPOSE[@]}" cp api:/var/lib/testgrid/artifacts "$work/artifacts" >/dev/null 2>&1
while IFS= read -r zip; do
  unzip -qq -o "$zip" -d "$work/unzipped/$(basename "$zip" .zip)" || echo "could not unzip $zip"
done < <(find "$work/artifacts" -name '*.zip')
"${COMPOSE[@]}" logs --no-color api worker >"$work/logs.txt"

fail=0
check() { if eval "$2"; then echo "  ok   $1"; else echo "  FAIL $1"; fail=1; fi; }
echo "--- positive controls"
log_key=$(sql "SELECT a.storage_key FROM artifacts a JOIN attempts t ON t.id = a.attempt_id JOIN run_targets g ON g.id = t.target_id WHERE g.run_id = '$run' AND a.kind = 'LOG'")
check "a log tail was stored for the failed attempt" '[ -n "$log_key" ]'
check "the log tail shows the masked canary line" 'grep -q "\[canary\] \[REDACTED\]" "$work/artifacts/$log_key"'
shots=$(sql "SELECT count(*) FROM artifacts a JOIN attempts t ON t.id = a.attempt_id JOIN run_targets g ON g.id = t.target_id WHERE g.run_id = '$run' AND a.kind = 'SCREENSHOT' AND a.case_key = 'cart-total'")
check "the failed case's screenshot was stored" '[ "$shots" -ge 1 ]'
check "the failed case's trace was withheld because it held the canary" 'grep -q "trace withheld: it contains a secret value" "$work/logs.txt"'

echo "--- scanning $(find "$work" -type f | wc -l | tr -d ' ') files for the canary"
hits=$(grep -r -a -l -F -- "$CANARY" "$work" || true)
if [ -n "$hits" ]; then
  echo "  FAIL the canary was found in:"
  sed "s|$work/|    |" <<<"$hits"
  fail=1
else
  echo "  ok   canary absent from API responses, events, database rows, stored artifacts and logs"
fi
[ "$fail" -eq 0 ] && echo "scan-canary: PASS" || echo "scan-canary: FAIL"
exit "$fail"
