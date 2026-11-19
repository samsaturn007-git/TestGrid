# Project board and progress system

## What is tracked?

`progress.json` is the canonical offline record, `PROGRESS.md` its generated dashboard, `BACKLOG.csv` the task-import spreadsheet-like artifact, and `docs/evidence/PRD-XX.md` the proof. Git commits and actual test results are the technical source of truth. No percentage can certify quality by itself.

## Recommended GitHub Project board

- **Milestones:** G0 Governance; G1 First Run (00–03); G2 Durable Platform (04–06); G3 Portfolio MVP (07–09); G4 Standout Engineering (10–12); G5 Release (13); Stretch (14–15).
- **Columns:** Backlog → Ready → In Progress → In Review → Verified → Blocked.
- **Labels:** `prd-00` ... `prd-15`, `backend`, `frontend`, `worker`, `data`, `infra`, `testing`, `security`, `docs`, `P0/P1/P2`.
- **Issue naming:** `[PRD-04/T3] Implement consumer manual ACK and DLQ`.
- **Definition of Verified:** Code merged, tests passing, failure tests run, docs updated, evidence recorded and personally reviewed.
- **WIP limit:** one PRD active, no more than two code issues actively changing at once.

Import `BACKLOG.csv` into your board or into Notion as tasks (GitHub Projects CSV import availability may change; alternatively use the CSV as an issue-generation source). Keep issue status synchronized manually or through your own future script; this kit does **not** claim to sync to GitHub.

## Weekly check-in template

```text
Week starting:
Active PRD / highest current milestone:
Tasks finished with real evidence:
Tests passed (commands + numbers):
One technical concept I can explain without AI:
Biggest unresolved bug / risk:
Planned next 3 tasks:
Hours spent on project / hours spent on DSA:
Scope items deferred:
```

## Decision flow before moving forward

1. Are all PRD T-tasks checked with real implementation?
2. Are all ACs tested, including negative/failure scenarios?
3. Is evidence file specific and current, not a copied template?
4. Did I personally run a representative happy path and failure path?
5. Can I explain the most important functions, their complexity and trade-offs?
6. Does the next PRD have its dependencies verified?

If not, remain in review. Codex must not close it based on a screenshot or a summary alone.

## Beginner DSA integration (parallel, not a separate app feature)

| Project phase | DSA practice theme | One real code connection |
|---|---|---|
| PRDs 00–03 | Arrays, strings, hash maps, state machines | Validate suite manifest, status map, dedupe IDs |
| PRD 04 | Queues, sets, concurrency basics | Message delivery and claim invariants |
| PRDs 05–07 | Sorting, sliding windows, hash maps, string normalization | Keyset pagination; rolling flip rate; O(n) run comparison; fingerprints |
| PRD 08–09 | Searching, retry/backoff, rule evaluation | CI wait loop with deadline; policy rules; 429 handling |
| PRD 10 | Invariants and event logs | Failure trace reasoning |
| PRD 11 | Heaps, greedy algorithms, bin-packing, complexity | LPT with aging (max-heap); duration-balanced shards (min-heap) |
| PRDs 12–13 | Complexity and design review | Query/index performance and architectural defense |

Study DSA independently for 60–120 minutes/day according to interview priorities. Do not count implementing a queue in an application as equivalent to knowing how to solve coding interview problems independently.
