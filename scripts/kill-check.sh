#!/usr/bin/env bash
# PRD-03 AC-4: kill -9 the worker mid-run; the target must stay non-terminal with a visibly
# expired lease (leased_until < now(), database clock) and never be marked PASSED.
# Recovery (reaper, retry) is PRD-04, so this script restarts the worker only at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
API=${API_URL:-http://127.0.0.1:8080}
PROJECT=00000000-0000-4000-8000-000000000001
TTL=30

body=$(printf '{"suiteKey":"checkout-smoke","targets":[{"executor":"LOCAL","engine":"chromium"}],"trigger":"CHAOS","idempotencyKey":"kill-check-%s","fixture":{"mode":"TIMEOUT","seed":42}}' "$(date +%s)")
run=$(curl -fsS -X POST -H 'content-type: application/json' -d "$body" "$API/api/v1/projects/$PROJECT/runs" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "created run $run (TIMEOUT fixture: the attempt runs for ~40 s)"

target_status() {
  curl -fsS "$API/api/v1/runs/$run" | python3 -c 'import json,sys; t=json.load(sys.stdin)["targets"][0]; print(t["status"])'
}
for _ in $(seq 1 60); do
  [ "$(target_status)" = RUNNING ] && break
  sleep 1
done
[ "$(target_status)" = RUNNING ] || { echo "target never reached RUNNING"; exit 1; }
sleep 3
echo "target RUNNING; sending SIGKILL to the worker container"
"${COMPOSE[@]}" kill -s SIGKILL worker >/dev/null
echo "waiting $((TTL + 5)) s for the $TTL s lease to run out (no heartbeats now)"
sleep $((TTL + 5))

echo "--- GET /api/v1/runs/$run"
curl -fsS "$API/api/v1/runs/$run" | python3 -c '
import json,sys
r=json.load(sys.stdin); t=r["targets"][0]; a=t["latestAttempt"]
print("run status:", r["status"], "| target status:", t["status"], "| finishedAt:", t["finishedAt"])
print("attempt", a["attemptNo"], a["status"], "worker", a["workerId"], "leasedUntil", a["leasedUntil"], "leaseExpired", a["leaseExpired"])
ok = r["status"] not in ("PASSED",) and t["status"] in ("CLAIMED","RUNNING") and a["leaseExpired"] is True
sys.exit(0 if ok else 1)'
echo "--- database (DB clock)"
"${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "
  SELECT a.status AS attempt, t.status AS target, r.status AS run,
         a.leased_until < now() AS lease_expired, round(extract(epoch FROM now() - a.leased_until)) AS expired_for_s,
         (SELECT count(*) FROM case_results c WHERE c.attempt_id = a.id) AS case_results
  FROM attempts a JOIN run_targets t ON t.id = a.target_id JOIN runs r ON r.id = t.run_id
  WHERE r.id = '"'$run'"'"'
echo "restarting the worker (the expired attempt stays as it is until PRD-04 adds the reaper)"
"${COMPOSE[@]}" up -d --wait worker >/dev/null
echo "kill-check OK"
