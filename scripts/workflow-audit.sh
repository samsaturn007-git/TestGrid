#!/usr/bin/env bash
# PRD-08 AC-5: the permissions and pinning audit of every GitHub workflow.
# - never pull_request_target (it runs a fork's PR with the base repository's secrets and a
#   write token);
# - a top-level `permissions: contents: read`, and no job that widens it;
# - every `uses:` pinned to a 40-hex commit SHA (a tag can be moved);
# - no repository secrets, no persisted checkout credentials;
# - no `${{ github.event.* }}` or `${{ inputs.* }}` inside a `run:` script, where a crafted PR title
#   or branch name would become shell (they go through `env:` instead).
set -euo pipefail
cd "$(dirname "$0")/.."
GREP=/usr/bin/grep
fail=0
problem() { echo "  $1"; fail=1; }

files=("$@"); [ ${#files[@]} -gt 0 ] || files=(.github/workflows/*.yml)
for wf in "${files[@]}"; do
  echo "$wf"
  $GREP -nE '^\s*pull_request_target\s*:' "$wf" && problem "uses pull_request_target"
  $GREP -qE '^permissions:\s*$' "$wf" || problem "no top-level permissions block"
  top=$(awk '/^permissions:/{on=1;next} on && /^[^ ]/{on=0} on' "$wf" | sed 's/#.*//' | $GREP -vE '^\s*$' || true)
  [ "$(echo "$top" | tr -d ' ')" = "contents:read" ] || problem "top-level permissions are not exactly contents: read ($top)"
  $GREP -nE '^    permissions:' "$wf" && problem "a job sets its own permissions"
  while IFS= read -r line; do
    ref=${line#*@}; ref=${ref%% *}
    [[ "$ref" =~ ^[0-9a-f]{40}$ ]] || problem "not pinned to a commit SHA: ${line#*uses: }"
  done < <($GREP -E '^\s*-?\s*uses:' "$wf" | $GREP -v '^\s*#' || true)
  $GREP -nE '\$\{\{\s*secrets\.' "$wf" && problem "uses repository secrets"
  $GREP -nE 'persist-credentials:\s*true' "$wf" && problem "persists checkout credentials"
  awk -v f="$wf" '
    /^ *(- )?run: *[|>]?/ { inrun=1; indent=match($0,/[^ ]/); if ($0 ~ /run: *[^|> ]/) check($0); next }
    inrun { if (match($0,/[^ ]/) <= indent && $0 !~ /^ *$/) inrun=0; else check($0) }
    function check(l) { if (l ~ /\$\{\{ *(github\.event|inputs)\./) { print "  " f ": untrusted expression inside run: " l; bad=1 } }
    END { exit bad }' "$wf" || fail=1
done
if [ "$fail" -eq 0 ]; then echo "workflow-audit OK"; else echo "workflow-audit FAILED"; fi
exit "$fail"
