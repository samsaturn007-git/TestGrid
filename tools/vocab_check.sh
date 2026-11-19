#!/usr/bin/env bash
# Vocabulary consistency check (PRD-00 FR-3 / AC-2).
# Every value of the canonical enums in contracts §2.1 must be spelled the same
# everywhere, and known non-canonical spellings must not appear as statuses.
# Exit 0 = consistent, 1 = a problem was found.
set -euo pipefail
cd "$(dirname "$0")/.."

CONTRACTS=docs/API_AND_DATA_CONTRACTS.md
DOCS=$(ls MASTER_PRD.md AGENTS.md docs/*.md prds/*.md)
fail=0

# 1. Every enum value declared in contracts §2.1 (the backticked lists in the table).
values=$(awk '/^### 2.1 Enums/{on=1;next} /^### 2.2/{on=0} on && /^\| `/' "$CONTRACTS" \
  | awk -F'|' '{print $3}' | grep -oE '[A-Za-z_]+' | sort -u)
echo "Canonical enum values (contracts §2.1): $(echo "$values" | wc -l | tr -d ' ')"
for v in $values; do
  if ! grep -qwF "$v" $DOCS; then echo "  value never used: $v"; fi
done

# 2. Known misspellings / synonyms that would signal a vocabulary drift.
# FAILING is allowed only as the case_health label (contracts §3, PRD-07).
bad='\b(CANCELED|TIMEDOUT|TIME_OUT|SUCCEEDED|SUCCESS|ERRORED|INFRA_FAILURE|INFRA_FAIL|IN_PROGRESS|PENDING|ABANDON|LEASED|PASSING)\b'
hits=$(grep -nHE "$bad" $DOCS | grep -v 'vocab_check' || true)
if [ -n "$hits" ]; then echo "Non-canonical spellings:"; echo "$hits"; fail=1; else echo "Non-canonical spellings: none"; fi

failing=$(grep -nHwE 'FAILING' $DOCS | grep -vE 'case_health|STABLE / FLAKY / FAILING|FLAKY.*FAILING' || true)
if [ -n "$failing" ]; then echo "FAILING used outside case_health labels:"; echo "$failing"; fail=1; fi

# 3. Problem-type slugs referenced as "<status> <slug>" must exist in contracts §6.
# HTTP codes only (2xx–5xx), not the tail of IDs such as "ADR-003".
for s in $(grep -ohE '(^|[^-0-9A-Za-z])[2-5][0-9]{2} [a-z]+(-[a-z]+)+\b' prds/*.md docs/*.md | awk '{print $NF}' | sort -u); do
  grep -qF "\`$s\`" "$CONTRACTS" || { echo "Problem slug not in contracts §6: $s"; fail=1; }
done

# 4. Event types used anywhere must be declared in contracts §5.
for e in $(grep -ohE '\b(RUN|TARGET|ATTEMPT|CASE|ARTIFACT|QUALITY)_[A-Z_]+\b' prds/*.md docs/*.md MASTER_PRD.md | sort -u); do
  grep -qw "$e" "$CONTRACTS" || { echo "Event type not in contracts §5: $e"; fail=1; }
done

[ "$fail" -eq 0 ] && echo "vocab_check OK" || echo "vocab_check FAILED"
exit "$fail"
