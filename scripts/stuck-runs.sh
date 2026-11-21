#!/usr/bin/env bash
# PRD-04 T7: the stuck_runs operator query (invariant I8, docs/RUNBOOK.md §3). Read-only.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
# The reaper's dispatch timeout, as Compose passes it to the API (.env, else the default).
timeout_ms=$(sed -n 's/^TESTGRID_DISPATCH_TIMEOUT_MS=\([0-9][0-9]*\)$/\1/p' .env 2>/dev/null | tail -1)
timeout_s=$(( ${timeout_ms:-300000} / 1000 ))
"${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v dispatch_timeout_s="$1" -P footer=off' sh "$timeout_s" < scripts/sql/stuck-runs.sql \
  | tee /dev/stderr | awk 'NR > 2 && NF {n++} END {print (n ? n : 0) " stuck target(s)"}' >&2
