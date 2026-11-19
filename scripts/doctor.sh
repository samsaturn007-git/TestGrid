#!/usr/bin/env bash
# Toolchain readiness: compares this machine with the pins in docs/VERSIONS.md.
# Exit 0 = ready, 1 = something must be fixed first. Prints a fix hint per failure.
set -uo pipefail
cd "$(dirname "$0")/.."

want_node=$(tr -d '[:space:]' < .nvmrc)
want_pnpm=$(sed -n 's/.*"packageManager": "pnpm@\([^"]*\)".*/\1/p' package.json)
fail=0
ok() { printf '  OK    %-14s %s\n' "$1" "$2"; }
bad() { printf '  FAIL  %-14s %s\n        fix: %s\n' "$1" "$2" "$3"; fail=1; }

echo "doctor:"

node_v=$(node -v 2>/dev/null | sed 's/^v//' || true)
if [ "$node_v" = "$want_node" ]; then ok node "$node_v"
else bad node "found '${node_v:-none}', want $want_node" "install Node $want_node (nvm install / brew upgrade node)"; fi

pnpm_v=$(pnpm -v 2>/dev/null || true)
if [ "$pnpm_v" = "$want_pnpm" ]; then ok pnpm "$pnpm_v"
else bad pnpm "found '${pnpm_v:-none}', want $want_pnpm" "npm install -g corepack@0.36.0 && corepack enable pnpm"; fi

java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
java_v=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1 || true)
if [ "$java_v" = "21" ]; then ok java "21 ($java_bin)"
else bad java "found '${java_v:-none}' at $java_bin, want 21" "install JDK 21 and export JAVA_HOME (the Makefile does this for Homebrew openjdk@21)"; fi

if docker info >/dev/null 2>&1; then ok docker "$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
else bad docker "engine not reachable" "start Docker (OrbStack on the reference Mac; Docker Desktop with WSL2 on Windows)"; fi

if compose_v=$(docker compose version --short 2>/dev/null); then ok compose "$compose_v"
else bad compose "docker compose plugin missing" "install the Docker Compose v2 plugin"; fi

if make_v=$(make -v 2>/dev/null | head -1); then ok make "$make_v"
else bad make "not found" "install GNU Make (xcode-select --install on macOS, build-essential on Linux)"; fi

[ "$fail" -eq 0 ] && echo "doctor: ready" || echo "doctor: fix the FAIL lines above"
exit "$fail"
