#!/usr/bin/env bash
# PRD-04 T7: inspect, replay or purge the dead-letter queue (docs/RUNBOOK.md §4).
#   bash scripts/dlq.sh list     each dead letter with what the database says about its target
#   bash scripts/dlq.sh replay   put every dead letter back on its engine queue (safe: the claim
#                                fences any message that is no longer the current dispatch)
#   CONFIRM=yes bash scripts/dlq.sh purge   drop them all (the reaper has recovered their targets)
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file .env)
action=${1:-list}
# A one-off container from the worker image: it has amqplib and the broker credentials, and it
# works while the worker service itself is stopped (a likely moment to need this).
tool() {
  DLQ_ACTION="$1" "${COMPOSE[@]}" run --rm --no-deps -T -e DLQ_ACTION -w /app/worker \
    --entrypoint node worker --input-type=module - < scripts/dlq.mjs 2>/dev/null
}

lookup() {
  # stdin: targetId TAB generation TAB messageId TAB queue TAB reason
  while IFS=$'\t' read -r target generation message queue reason; do
    if [[ "$target" =~ ^[0-9a-f-]{36}$ ]]; then
      db=$("${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F" " -c "$1"' sh \
        "SELECT status, dispatch_generation, (dispatched_at IS NOT NULL) FROM run_targets WHERE id = '$target'")
      read -r status current dispatched <<<"${db:-missing - -}"
      if [ "$status" = QUEUED ] && [ "$current" = "$generation" ] && [ "$dispatched" = t ]; then
        verdict="claimable: a replay runs it now"
      elif [ "$status" = QUEUED ]; then
        verdict="stale: the reaper already sent generation $current"
      else
        verdict="stale: target is $status"
      fi
    else
      verdict="unparseable"
    fi
    printf '%s gen %s (%s from %s): %s\n' "$target" "$generation" "$reason" "$queue" "$verdict"
  done
}

case "$action" in
  list)
    # Counted from what the tool read: the broker's own queue counters lag by a few seconds.
    lines=$(tool list)
    echo "tg.targets.dead holds $(printf '%s' "$lines" | grep -c . || true) message(s)"
    [ -z "$lines" ] || printf '%s\n' "$lines" | lookup
    ;;
  replay)
    tool replay
    ;;
  purge)
    [ "${CONFIRM:-}" = yes ] || { echo "purge drops every dead letter; run with CONFIRM=yes"; exit 1; }
    "${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl purge_queue tg.targets.dead
    ;;
  *) echo "usage: scripts/dlq.sh list|replay|purge"; exit 2 ;;
esac
