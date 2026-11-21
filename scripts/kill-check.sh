#!/usr/bin/env bash
# PRD-04 AC-4 (Q-004), updated from PRD-03 AC-4 (CONFLICT_REGISTER N16): kill -9 the worker
# mid-run and restart it. The broker redelivers the unacked message, the restarted worker's claim
# gets 409 (the target is still RUNNING) and acks it. Once the lease runs out the reaper marks
# attempt 1 ABANDONED (LEASE_EXPIRED) and keeps it, requeues the target with a backoff, and the
# dispatcher sends a new generation that attempt 2 claims and runs to a real result.
#
# Recovery bound checked here, from the kill to attempt 2's claim:
#   lease TTL (30 s, the last heartbeat may be up to 10 s before the kill, so ≤ 30 s after it)
#   + reaper interval (5 s) + first backoff (2 s + 20 %) + dispatch tick and claim, with margin.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
API=${API_URL:-http://127.0.0.1:8080}
PROJECT=00000000-0000-4000-8000-000000000001
BOUND_S=${KILL_CHECK_BOUND_S:-45}

sql() {
  "${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F" | " -v ON_ERROR_STOP=1 -c "$1"' sh "$1"
}

# TIMEOUT fixture: each attempt runs ~40 s, long enough to kill it reliably while RUNNING.
body=$(printf '{"suiteKey":"checkout-smoke","targets":[{"executor":"LOCAL","engine":"chromium"}],"trigger":"CHAOS","idempotencyKey":"kill-check-%s","fixture":{"mode":"TIMEOUT","seed":42}}' "$(date +%s)")
run=$(curl -fsS -X POST -H 'content-type: application/json' -d "$body" "$API/api/v1/projects/$PROJECT/runs" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "created run $run (TIMEOUT fixture: each attempt runs ~40 s)"

target_status() {
  curl -fsS "$API/api/v1/runs/$run" | python3 -c 'import json,sys; print(json.load(sys.stdin)["targets"][0]["status"])'
}
for _ in $(seq 1 60); do
  [ "$(target_status)" = RUNNING ] && break
  sleep 1
done
[ "$(target_status)" = RUNNING ] || { echo "target never reached RUNNING"; exit 1; }
sleep 3
echo "target RUNNING; sending SIGKILL to the worker container and starting it again"
killed_at=$(date +%s)
"${COMPOSE[@]}" kill -s SIGKILL worker >/dev/null
"${COMPOSE[@]}" up -d --wait worker >/dev/null

echo "waiting for the reaper to abandon attempt 1 and for attempt 2 to be claimed (bound ${BOUND_S} s)"
recovered=""
for _ in $(seq 1 $((BOUND_S + 30))); do
  n=$(sql "SELECT count(*) FROM attempts a JOIN run_targets t ON t.id = a.target_id WHERE t.run_id = '$run'")
  if [ "$n" -ge 2 ]; then recovered=$(( $(date +%s) - killed_at )); break; fi
  sleep 1
done
[ -n "$recovered" ] || { echo "no second attempt within $((BOUND_S + 30)) s"; exit 1; }
echo "attempt 2 claimed ${recovered} s after the kill"

echo "waiting for attempt 2 to finish"
for _ in $(seq 1 120); do
  case "$(target_status)" in PASSED|FAILED|TIMED_OUT|INFRA_ERROR|CANCELLED) break ;; esac
  sleep 1
done

echo "--- attempts (database)"
sql "SELECT a.attempt_no, a.status, coalesce(a.error_class, '-'), a.worker_id,
            (SELECT count(*) FROM case_results c WHERE c.attempt_id = a.id) AS cases
     FROM attempts a JOIN run_targets t ON t.id = a.target_id
     WHERE t.run_id = '$run' ORDER BY a.attempt_no"
echo "--- events"
sql "SELECT string_agg(type, ' > ' ORDER BY id) FROM run_events WHERE run_id = '$run'"
echo "--- run"
curl -fsS "$API/api/v1/runs/$run" | python3 -c '
import json,sys
r=json.load(sys.stdin); t=r["targets"][0]
print("run status:", r["status"], "| target status:", t["status"], "| attempts:", t["attemptCount"])'

first=$(sql "SELECT a.status || ' ' || a.error_class || ' ' ||
                    (SELECT count(*) FROM case_results c WHERE c.attempt_id = a.id)
             FROM attempts a JOIN run_targets t ON t.id = a.target_id
             WHERE t.run_id = '$run' AND a.attempt_no = 1")
second=$(sql "SELECT a.status FROM attempts a JOIN run_targets t ON t.id = a.target_id
              WHERE t.run_id = '$run' AND a.attempt_no = 2")
target=$(target_status)
retry=$(sql "SELECT count(*) FROM run_events WHERE run_id = '$run' AND type = 'TARGET_RETRY_SCHEDULED'")
fail=0
[ "$first" = "ABANDONED LEASE_EXPIRED 0" ] || { echo "attempt 1 is '$first', want 'ABANDONED LEASE_EXPIRED 0'"; fail=1; }
[ "$second" = TIMED_OUT ] || { echo "attempt 2 is '$second', want TIMED_OUT"; fail=1; }
[ "$target" = TIMED_OUT ] || { echo "target is '$target', want TIMED_OUT"; fail=1; }
[ "$retry" = 1 ] || { echo "TARGET_RETRY_SCHEDULED events: $retry, want 1"; fail=1; }
[ "$recovered" -le "$BOUND_S" ] || { echo "recovery took ${recovered} s, bound ${BOUND_S} s"; fail=1; }
[ "$fail" -eq 0 ] || exit 1
echo "kill-check OK"
