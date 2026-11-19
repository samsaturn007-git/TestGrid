#!/usr/bin/env bash
# Readiness check: curl every service health endpoint on 127.0.0.1 (PRD-01 FR-2 / T6).
# Exit 0 = all healthy, 1 = at least one service failed after retries.
set -euo pipefail
cd "$(dirname "$0")/.."

# Ports come from .env when present, else the .env.example defaults.
env_file=.env
[ -f "$env_file" ] || env_file=.env.example
port() { /usr/bin/grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2 || true; }
API_PORT=${API_PORT:-$(port API_PORT)}
WEB_PORT=${WEB_PORT:-$(port WEB_PORT)}
DEMO_SHOP_PORT=${DEMO_SHOP_PORT:-$(port DEMO_SHOP_PORT)}

fail=0
check() {
  local name=$1 url=$2 body
  for _ in $(seq 1 15); do
    if body=$(curl -fsS --max-time 3 "$url" 2>/dev/null); then
      printf '  %-10s OK   %s %s\n' "$name" "$url" "$body"
      return 0
    fi
    sleep 1
  done
  printf '  %-10s FAIL %s\n' "$name" "$url"
  fail=1
}

echo "health:"
check api "http://127.0.0.1:${API_PORT:-8080}/healthz"
check web "http://127.0.0.1:${WEB_PORT:-3000}/healthz"
check demo-shop "http://127.0.0.1:${DEMO_SHOP_PORT:-4173}/healthz"
exit "$fail"
