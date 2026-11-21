# TestGrid — Live Project Progress

> Generated from `progress.json` by `python3 tools/progress.py report`. Status is user/agent-reported and requires independent evidence verification.

**Core PRDs complete:** 4/14  |  **Core checklist progress:** 31/93 tasks (33.3%)

| PRD | Status | Task progress | Effort | Dependencies | Evidence |
|---|---|---:|---|---|---|
| [PRD-00](prds/PRD-00-governance.md) | done | 6/6 | 4–6 h | — | [docs/evidence/PRD-00.md](docs/evidence/PRD-00.md) |
| [PRD-01](prds/PRD-01-monorepo-and-fixture.md) | done | 7/7 | 10–16 h | PRD-00 | [docs/evidence/PRD-01.md](docs/evidence/PRD-01.md) |
| [PRD-02](prds/PRD-02-domain-and-api.md) | done | 6/6 | 14–20 h | PRD-01 | [docs/evidence/PRD-02.md](docs/evidence/PRD-02.md) |
| [PRD-03](prds/PRD-03-single-worker-runner.md) | done | 7/7 | 14–22 h | PRD-02 | [docs/evidence/PRD-03.md](docs/evidence/PRD-03.md) |
| [PRD-04](prds/PRD-04-durable-queue.md) | in_progress | 5/7 | 20–30 h | PRD-03 | — |
| [PRD-05](prds/PRD-05-live-dashboard.md) | not_started | 0/7 | 14–20 h | PRD-04 | — |
| [PRD-06](prds/PRD-06-artifacts-and-timeline.md) | not_started | 0/6 | 12–18 h | PRD-05 | — |
| [PRD-07](prds/PRD-07-failure-intelligence.md) | not_started | 0/7 | 16–24 h | PRD-06 | — |
| [PRD-08](prds/PRD-08-ci-quality-gates.md) | not_started | 0/7 | 14–20 h | PRD-07 | — |
| [PRD-09](prds/PRD-09-browserstack-adapter.md) | not_started | 0/7 | 8–14 h | PRD-08 | — |
| [PRD-10](prds/PRD-10-chaos-and-recovery.md) | not_started | 0/6 | 12–20 h | PRD-09 | — |
| [PRD-11](prds/PRD-11-adaptive-scheduler.md) | not_started | 0/7 | 16–24 h | PRD-10 | — |
| [PRD-12](prds/PRD-12-security-and-deploy.md) | not_started | 0/6 | 16–24 h | PRD-11 | — |
| [PRD-13](prds/PRD-13-portfolio-and-release.md) | not_started | 0/7 | 8–12 h | PRD-12 | — |
| [PRD-14 (optional)](prds/PRD-14-visual-regression-optional.md) | not_started | 0/5 | 8–14 h | PRD-13 | — |
| [PRD-15 (optional)](prds/PRD-15-ai-assistance-optional.md) | not_started | 0/5 | 6–12 h | PRD-13 | — |

## Milestones

- **G0 Design:** PRD-00.
- **G1 First runnable run:** PRD-01 to PRD-03.
- **G2 Durable system:** PRD-04 to PRD-06.
- **G3 Portfolio MVP:** PRD-07 to PRD-09.
- **G4 Engineering differentiation:** PRD-10 to PRD-12.
- **G5 Presentation and honest release:** PRD-13.
- **Optional:** PRD-14 to PRD-15.

## Next action
Continue [PRD-04](prds/PRD-04-durable-queue.md) (in_progress); complete unfinished checkboxes and add genuine evidence.

## Progress update commands

```bash
python3 tools/progress.py status
python3 tools/progress.py lint
python3 tools/progress.py start PRD-00
python3 tools/progress.py check PRD-00 T1
python3 tools/progress.py done PRD-00 --evidence docs/evidence/PRD-00.md
```
