#!/usr/bin/env bash
# PRD-04 T7: the stuck_runs operator query (invariant I8, docs/RUNBOOK.md §3). Read-only.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
"${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -P footer=off' < scripts/sql/stuck-runs.sql \
  | tee /dev/stderr | awk 'NR > 2 && NF {n++} END {print (n ? n : 0) " stuck target(s)"}' >&2
