#!/usr/bin/env bash
# PRD-04 AC-3 (Q-008) and AC-4 (Q-004) with the real worker image and its TG_FAULT points.
#   after_complete_before_ack: the worker completes, then SIGKILLs itself before the ack. The
#     restarted worker gets the message back, its claim answers 409 and it acks: one attempt.
#   after_claim: the worker SIGKILLs itself right after the claim. The restarted worker's claim
#     of the redelivered message answers 409; the lease runs out, the reaper abandons attempt 1
#     and attempt 2 runs to PASSED.
# Needs TESTGRID_PROFILE=local in .env (the fault points are refused otherwise).
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
API=${API_URL:-http://127.0.0.1:8080}
PROJECT=00000000-0000-4000-8000-000000000001

sql() {
  "${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F" | " -v ON_ERROR_STOP=1 -c "$1"' sh "$1"
}
worker_with() {
  TG_FAULT_WORKER="$1" "${COMPOSE[@]}" up -d --force-recreate --no-deps --wait worker >/dev/null 2>&1
}
worker_state() {
  docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$("${COMPOSE[@]}" ps -aq worker | head -1)"
}
create_run() {
  body=$(printf '{"suiteKey":"catalog-regression","targets":[{"executor":"LOCAL","engine":"chromium"}],"trigger":"CHAOS","idempotencyKey":"crash-check-%s-%s","fixture":{"mode":"PASS","seed":42}}' "$1" "$(date +%s)")
  curl -fsS -X POST -H 'content-type: application/json' -d "$body" "$API/api/v1/projects/$PROJECT/runs" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}
attempts_of() {
  sql "SELECT string_agg(a.attempt_no || ':' || a.status || ':' || coalesce(a.error_class, '-'), ', ' ORDER BY a.attempt_no)
       FROM attempts a JOIN run_targets t ON t.id = a.target_id WHERE t.run_id = '$1'"
}
target_of() { sql "SELECT status FROM run_targets WHERE run_id = '$1'"; }
wait_exit() {
  for _ in $(seq 1 90); do
    case "$(worker_state)" in exited*) return 0 ;; esac
    sleep 1
  done
  echo "the worker did not die at the fault point"; return 1
}
queue_empty() {
  "${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q list_queues name messages messages_unacknowledged \
    | awk '$1 ~ /^tg\.targets\./ && ($2 != 0 || $3 != 0) {bad=1} END {exit bad}'
}
# Counted per worker container (a recreated container starts a new log).
stale_acks() { "${COMPOSE[@]}" logs worker 2>&1 | grep -c '"stale dispatch; acking"' || true; }
fail=0

echo "== after_complete_before_ack"
worker_with after_complete_before_ack
run=$(create_run ack)
wait_exit
echo "worker died: $(worker_state); target $(target_of "$run"); attempts $(attempts_of "$run")"
worker_with ""
for _ in $(seq 1 30); do queue_empty && break; sleep 1; done
got=$(attempts_of "$run")
echo "after restart: target $(target_of "$run"); attempts $got"
[ "$(target_of "$run")" = PASSED ] && [ "$got" = "1:PASSED:-" ] || { echo "FAIL: want PASSED with one attempt"; fail=1; }
queue_empty || { echo "FAIL: messages left in the engine queues"; fail=1; }
stale=$(stale_acks)
echo "redelivery refused (409) and acked by the restarted worker: $stale"
[ "$stale" -ge 1 ] || { echo "FAIL: the redelivered message was not refused"; fail=1; }

echo "== after_claim"
worker_with after_claim
run=$(create_run claim)
wait_exit
echo "worker died: $(worker_state); target $(target_of "$run"); attempts $(attempts_of "$run")"
killed=$(date +%s)
worker_with ""
for _ in $(seq 1 90); do [ "$(target_of "$run")" = PASSED ] && break; sleep 1; done
got=$(attempts_of "$run")
echo "after restart: target $(target_of "$run") $(( $(date +%s) - killed )) s after the kill; attempts $got"
[ "$got" = "1:ABANDONED:LEASE_EXPIRED, 2:PASSED:-" ] || { echo "FAIL: want attempt 1 ABANDONED, attempt 2 PASSED"; fail=1; }
stale=$(stale_acks)
echo "redelivery refused (409) and acked by the restarted worker: $stale"
[ "$stale" -ge 1 ] || { echo "FAIL: the redelivered message was not refused"; fail=1; }
[ "$fail" -eq 0 ] || exit 1
echo "crash-check OK"
