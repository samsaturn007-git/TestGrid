# cli

The TestGrid CLI (PRD-08 FR-4, contracts §11). It submits a run, waits for it, and turns the
server's quality evaluation into an exit code a CI job can gate on. TypeScript run directly by
Node 26, with no dependencies beyond Node itself.

```bash
pnpm testgrid run --project demo --suite checkout-smoke --engines chromium,firefox --wait
```

```bash
node cli/src/main.ts gate <runId> --wait --timeout 10m --json
```

## Commands

| Command | Does | Exit |
|---|---|---|
| `run --project <slug\|id> --suite <key> --engines <list> [--fixture MODE [--seed N]] [--wait] [--timeout 15m]` | Creates a run (`trigger` CI inside GitHub Actions, else MANUAL; `--trigger` overrides). With `--wait`, polls until the run is finished and evaluated | without `--wait`: 0 once accepted; with it, the gate's code |
| `status <runId>` | The run and its targets | 0, or 2 / 3 on errors |
| `gate <runId> [--wait] [--timeout 15m]` | The quality evaluation | the gate's code; 2 for an unfinished run without `--wait` |
| `compare --base last-green\|<runId> --head <runId>` | The PRD-07 comparison, changed rows only | 0, or 2 / 3 on errors |

Every command takes `--api-url` (or `TESTGRID_API_URL`, default `http://127.0.0.1:8080`) and
`--json`.

## Exit codes (contracts §2.4)

| Code | Meaning |
|---|---|
| 0 | `PASS`: the server evaluated the run and every blocking rule passed |
| 1 | `FAIL`: test evidence broke a blocking rule (critical case, too many failures, a blocking duration regression) |
| 2 | `INDETERMINATE`: a required target missing, `INFRA_ERROR`, cancelled or timed out as a whole; the run not finished and evaluated before `--timeout`; or the API unreachable until then |
| 3 | Usage, configuration or authentication: bad arguments, an unknown project or suite, `400`, `401`, `403`, `404`, `409`, `422` |

Nothing but a server-side `PASS` gives 0. A deadline is always 2, whatever state the run was in.

## Behaviour

- **Deadline:** `--timeout` (`90s`, `15m`, `1h`; 1 s … 6 h) bounds the whole command, retries
  included: 15 minutes with `--wait`, otherwise 60 s.
- **Polling:** 1 s doubling to 10 s, ±20 % jitter, never past the deadline.
- **Retries:** network errors, the 10 s per-request timeout, `429` and `5xx` are retried with the
  same backoff until the deadline. Other `4xx` answers are final (exit 3).
- **Idempotency key:** `tgcli:` + sha256 of repo, commit, CI ref, suite, sorted engines and fixture,
  so a retried step of the same CI job replays its run (`200`, "replayed") instead of starting
  another. Inside GitHub Actions these come from `GITHUB_REPOSITORY`, `GITHUB_SHA`,
  `GITHUB_HEAD_REF` / `GITHUB_REF_NAME` and `gh:$GITHUB_RUN_ID/$GITHUB_RUN_ATTEMPT`, so "Re-run
  jobs" (a new attempt) creates a new run. Without a CI ref the key is random. `--repo`,
  `--commit`, `--branch`, `--ci-ref` and `--idempotency-key` override them.
- **Token:** read from `TESTGRID_TOKEN` and sent as `Authorization: Bearer`. A `--token` flag is
  refused, so the token never lands in a process list or shell history. It is never printed.
- **Output:** a readable report on stdout, or exactly one JSON document with `--json` (errors too:
  `{command, exitCode, outcome, error {message, status, problemType}}`). Progress and errors go to
  stderr.

## Tests

`pnpm --filter cli test`: argument parsing, backoff and durations, the idempotency key, every exit
path against a fake API with a fake clock (CI-001 … CI-004), and one pass over real HTTP including
a server that never answers.
