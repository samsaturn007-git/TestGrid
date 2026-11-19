# Evidence — PRD-XX

**PRD:** [exact path]  
**Branch / commits:** [actual SHA]  
**Reviewer:** [human]  
**Date:** [UTC date]  
**Outcome:** [pass / fail / blocked]

> `tools/progress.py done` refuses this file while any bracketed placeholder remains, while any AC from the PRD is missing, or unless the outcome reads `pass`.

## Acceptance criteria with evidence

One row per AC in the PRD. Add rows as needed.

| Criterion | Pass? | Test ID(s) | Proof (file, test and observable behaviour) |
|---|---|---|---|
| AC-1 | | | |
| AC-2 | | | |
| AC-3 | | | |

## Tasks completed

| Task | Commit(s) | Notes |
|---|---|---|
| T1 | | |

## Changed files

- `path/to/file` — why

## Actual executed commands and outputs

```text
$ <command>
<actual output and exit code>
```

## Negative / failure tests

- Case:
- How it was injected:
- Observed state and recovery:

## Security and threat check

- Input restrictions checked:
- Secret or artifact leak scan (canary):
- New attack surface introduced, and its mitigation:

## Skipped or mocked tests

- Test / reason / mocked or live:

## Screenshots / demo / raw data links

- Exact repository-relative paths or hosted URLs (never invented):

## Known limitations and deferred improvements

-

## Explain-back review (developer's own words)

1. The three most important engineering decisions in this PRD, and why:
2. What happens if the main operation fails halfway through?
3. What would you change for production?
