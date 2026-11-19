# Codex playbook — run one PRD at a time

Use Codex in a copy of this folder or in a newly initialized GitHub repository containing these documents. Codex can read `AGENTS.md`. Each prompt below is ready for reuse.

## Prompt A — begin a PRD

```text
You are my senior engineer and coding mentor for TestGrid. Read AGENTS.md,
MASTER_PRD.md, docs/ARCHITECTURE.md, docs/API_AND_DATA_CONTRACTS.md,
docs/SECURITY_AND_TEST_STRATEGY.md, and prds/PRD-XX-<actual-filename>.md.
Inspect the current repository before changing anything. Scope ONLY this PRD.
First summarize prerequisites, user-visible vertical slice, files you will
create/change, contracts affected, security risks, and commands to verify.
Then implement in small reviewable commits with tests. Never fabricate test
results, benchmarks or integration outcomes. Use the PRD acceptance checklist.
Reference test IDs from docs/SYSTEM_TEST_MATRIX.md in tests and evidence.
At completion create docs/evidence/PRD-XX.md with real results and an
explain-back quiz (3 questions); tell me what to review before closure.
```

**Replace `PRD-XX-<actual-filename>.md` with the exact file path.**

## Prompt B — continue after coding chunk

```text
Stay within the active PRD-XX. Show the current diff summary and which numbered
tasks/acceptance criteria are satisfied with evidence. Identify the next smallest
vertical slice. Implement it, run focused tests, then update the evidence file.
Do not mark unfinished requirements as done.
```

## Prompt C — reviewer / adversarial test pass

```text
Act as a skeptical BrowserStack SDE I interviewer reviewing only PRD-XX.
Look for logical races, insecure URLs/uploads, at-least-once delivery mistakes,
missing failure paths, bad indexes, weak integration tests, and unsupported
performance claims. Add failing tests for genuine bugs, repair them, rerun the
suite, and write a concise review summary with severity and evidence.
```

## Prompt D — close a PRD

```text
Verify every T-task and acceptance criterion in PRD-XX against actual code.
Include commands, exit codes, observed output, changed files, tests skipped,
real screenshots/URLs if available, a manual smoke walkthrough, known limits,
and the commit SHA in docs/evidence/PRD-XX.md.
Only if ALL pass, mark tasks complete in progress.json using tools/progress.py;
then ask me to inspect the evidence and approve completion. Do not advance
to another PRD automatically.
```

## Prompt E — resume a new Codex session

```text
We are continuing TestGrid. Read AGENTS.md, MASTER_PRD.md, PROGRESS.md,
progress.json, docs/ARCHITECTURE.md, and the in-progress PRD. Inspect git log
and actual files. Reconcile documented progress with code and tests; identify
any undocumented or falsely completed item. Resume from the first unfinished
task. Do not rewrite finished work unless a failing test justifies it.
```

## Prompt F — interview-mode explanation

```text
Review the latest completed TestGrid PRD. Quiz me as a beginner-to-intermediate
SDE I candidate. Ask one question at a time about code I actually wrote:
algorithmic complexity, database transaction, thread safety, HTTP failure,
test assertion, security or trade-off. Grade my explanation and show the exact
source file/functions I should study. Do not provide memorized model answers
before I attempt a response.
```

## Execution pattern

- Run `python3 tools/progress.py lint` (tracker matches PRD files), then `status` and `start PRD-XX`.
- Branch for that PRD. Run Prompt A. Use Prompt B after each implementation chunk.
- Run Prompt C as an adversarial pass. Run Prompt D and manually review.
- Mark each checked task `python3 tools/progress.py check PRD-XX Tn` and finish with evidence `python3 tools/progress.py done PRD-XX --evidence docs/evidence/PRD-XX.md`.
- `python3 tools/progress.py report` regenerates `PROGRESS.md`.

## Definition of “a Codex phase is finished”

The application runs, tests pass in a clean environment, failure modes are verified, required docs are updated, an evidence report exists, and you can explain the significant code without Codex. A green-looking frontend screenshot alone does not count.
