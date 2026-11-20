#!/usr/bin/env bash
# PRD-02 AC-1 / API-006 against the running Compose stack: create a run, restart the API
# container, and check the run and its targets read back unchanged (and the key still replays).
# Needs `make up` first. Talks only to the API on 127.0.0.1.
set -euo pipefail

api="http://127.0.0.1:${API_PORT:-8080}"
project="00000000-0000-4000-8000-000000000001"
compose=(docker compose -f infra/compose/compose.yaml --env-file .env)
key="restart-check-$(date +%s)-$$"
body='{"suiteKey":"checkout-smoke","targets":[{"executor":"LOCAL","engine":"chromium"},{"executor":"LOCAL","engine":"firefox"}],"trigger":"MANUAL","idempotencyKey":"'"$key"'"}'
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

status=$(curl -sS -o "$work/created.json" -w '%{http_code}' -X POST "$api/api/v1/projects/$project/runs" \
  -H 'content-type: application/json' -d "$body")
if [ "$status" != "202" ]; then
  echo "create failed: HTTP $status"; cat "$work/created.json"; exit 1
fi
run_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$work/created.json")
curl -sS -f "$api/api/v1/runs/$run_id" -o "$work/before.json"
echo "created run $run_id (HTTP 202)"

echo "restarting the api container..."
start=$(date +%s)
"${compose[@]}" restart api >/dev/null 2>&1
"${compose[@]}" up -d --no-recreate --wait --wait-timeout 120 api >/dev/null 2>&1
echo "api healthy again after $(( $(date +%s) - start ))s"

curl -sS -f "$api/api/v1/runs/$run_id" -o "$work/after.json"
python3 - "$work/before.json" "$work/after.json" <<'PY'
import json, sys
before, after = (json.load(open(p)) for p in sys.argv[1:3])
if before != after:
    print("MISMATCH after restart"); print(json.dumps(before, indent=1)); print(json.dumps(after, indent=1)); sys.exit(1)
print(f"run {after['id']} unchanged after restart: status {after['status']}, "
      f"targets {[t['targetKey'] for t in after['targets']]}")
PY

replay=$(curl -sS -o "$work/replay.json" -D "$work/replay.headers" -w '%{http_code}' -X POST \
  "$api/api/v1/projects/$project/runs" -H 'content-type: application/json' -d "$body")
replay_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$work/replay.json")
if [ "$replay" != "200" ] || [ "$replay_id" != "$run_id" ] || ! /usr/bin/grep -qi '^idempotent-replay: true' "$work/replay.headers"; then
  echo "replay after restart failed: HTTP $replay, id $replay_id"; exit 1
fi
echo "same key after restart: HTTP 200, Idempotent-Replay: true, same run id"
echo "restart-check OK"
