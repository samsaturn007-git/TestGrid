#!/usr/bin/env bash
# PRD-02 FR-9 contract drift check. Run after `make contracts` has regenerated the files
# from the code: any difference from the committed versions fails the build.
set -euo pipefail

files=(api/openapi.yaml packages/contracts/src/api.ts)

if ! git diff --exit-code -- "${files[@]}"; then
  echo "contract drift: the code no longer matches the committed contract files." >&2
  echo "run 'make contracts' and commit api/openapi.yaml and packages/contracts/src/api.ts" >&2
  exit 1
fi

untracked=$(git status --porcelain -- "${files[@]}")
if [ -n "$untracked" ]; then
  echo "contract files are not committed:" >&2
  echo "$untracked" >&2
  exit 1
fi

echo "contracts-check OK: api/openapi.yaml and packages/contracts/src/api.ts match the code"
