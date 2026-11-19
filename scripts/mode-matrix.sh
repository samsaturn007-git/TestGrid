#!/usr/bin/env bash
# PRD-01 AC-2 / NFR: run checkout-smoke in every fixture mode, 3 times each, starting
# from `make reset`, and check that repeated runs give identical per-case results.
# Output: one summary file per run plus a verdict, under $OUT (default worker/test-results/modes).
# Exit 0 = every mode was identical across its repeats, 1 = a mode drifted.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=${OUT:-worker/test-results/modes}
REPEATS=${REPEATS:-3}
SHOP=http://127.0.0.1:$(/usr/bin/grep -E '^DEMO_SHOP_PORT=' .env 2>/dev/null | cut -d= -f2 || echo 4173)

# mode:seed pairs. AUTOMATION_FLAKE is run with three seeds whose first two tries are
# late/on-time (0 → FLAKY), late/late (7 → FAILED) and on-time (42 → PASSED).
RUNS=(PASS:42 PRODUCT_DEFECT:42 SELECTOR_DRIFT:42 TIMEOUT:42 AUTOMATION_FLAKE:0 AUTOMATION_FLAKE:7 AUTOMATION_FLAKE:42)

[ "${SKIP_RESET:-0}" = 1 ] || make --no-print-directory reset
summary_dir=$(mktemp -d)
mkdir -p "$OUT"
drift=0

for run in "${RUNS[@]}"; do
  mode=${run%%:*} seed=${run##*:}
  for i in $(seq 1 "$REPEATS"); do
    curl -fsS -X POST "$SHOP/__fixture/reset" >/dev/null
    FIXTURE_MODE=$mode FIXTURE_SEED=$seed pnpm --filter worker exec playwright test \
      --project=chromium >/dev/null 2>&1 && code=0 || code=$?
    # Copy before the next run cleans worker/test-results.
    node scripts/summarize-playwright.mjs worker/test-results/results.json > "$summary_dir/$mode-$seed-$i.txt"
    echo "exit=$code" >> "$summary_dir/$mode-$seed-$i.txt"
  done
  if for i in $(seq 2 "$REPEATS"); do cmp -s "$summary_dir/$mode-$seed-1.txt" "$summary_dir/$mode-$seed-$i.txt" || exit 1; done; then
    verdict="identical x$REPEATS"
  else
    verdict="DIFFERENT"; drift=1
  fi
  echo "=== $mode seed=$seed: $verdict"
  cat "$summary_dir/$mode-$seed-1.txt"
done | tee "$OUT/mode-matrix.txt"

cp "$summary_dir"/*.txt "$OUT"/
/usr/bin/grep -q DIFFERENT "$OUT/mode-matrix.txt" && drift=1
exit "$drift"
