#!/usr/bin/env bash
# PRD-03 FR-6: SIGTERM the worker mid-run. It must stop claiming, give the current attempt the
# grace period (TESTGRID_SHUTDOWN_GRACE_MS, 20 s), then stop Playwright and report
# INFRA_ERROR / WORKER_SHUTDOWN. Uses the TIMEOUT fixture so the attempt outlasts the grace.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
API=${API_URL:-http://127.0.0.1:8080}
PROJECT=00000000-0000-4000-8000-000000000001

body=$(printf '{"suiteKey":"checkout-smoke","targets":[{"executor":"LOCAL","engine":"chromium"}],"trigger":"CHAOS","idempotencyKey":"sigterm-check-%s","fixture":{"mode":"TIMEOUT","seed":42}}' "$(date +%s)")
run=$(curl -fsS -X POST -H 'content-type: application/json' -d "$body" "$API/api/v1/projects/$PROJECT/runs" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "created run $run"
status() { curl -fsS "$API/api/v1/runs/$run" | python3 -c 'import json,sys; print(json.load(sys.stdin)["targets"][0]["status"])'; }
for _ in $(seq 1 120); do [ "$(status)" = RUNNING ] && break; sleep 1; done
[ "$(status)" = RUNNING ] || { echo "target never reached RUNNING"; exit 1; }
sleep 3
echo "target RUNNING; docker compose stop worker (SIGTERM)"
t0=$(date +%s)
"${COMPOSE[@]}" stop worker >/dev/null 2>&1
echo "worker container stopped after $(( $(date +%s) - t0 )) s"
curl -fsS "$API/api/v1/runs/$run/results" | python3 -c '
import json,sys
r=json.load(sys.stdin); t=r["targets"][0]; a=t["attempts"][0]
print("run", r["status"], "| target", t["status"], "| attempt", a["attempt"]["status"], a["attempt"]["errorClass"], "| detail:", a["errorDetail"], "| cases", len(a["caseResults"]))
sys.exit(0 if t["status"] == "INFRA_ERROR" and a["attempt"]["errorClass"] == "WORKER_SHUTDOWN" else 1)'
echo "--- worker log during shutdown"
"${COMPOSE[@]}" logs worker --no-color --since 2m 2>&1 | grep -oE '"msg":"[^"]+"' | tail -6
"${COMPOSE[@]}" up -d --wait worker >/dev/null 2>&1
echo "sigterm-check OK"
