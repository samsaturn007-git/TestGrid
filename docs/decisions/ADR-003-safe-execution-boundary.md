# ADR-003: Safe execution boundary — manifest-only suites, no client URLs or code

**Date:** 2026-11-19 | **Status:** Proposed (accepted once the developer signs off in `docs/evidence/PRD-00.md`)

## Context
- A test-orchestration platform executes code in browsers by design. Accepting code, URLs or CLI flags from clients turns it into a remote-code-execution and SSRF service.
- TestGrid is local-first and portfolio-grade: there is no sandbox design, tenant isolation or abuse team. The safe choice is to make dangerous inputs *unrepresentable*, not merely validated.
- Threats in scope (SECURITY threat model): RCE via uploaded specs or flag injection, SSRF via target URLs, secret disclosure, forged worker callbacks.

## Options considered
- **A. Manifest-only suites (chosen).** Suites, cases, tags and `baseUrlRef` live in the checked-in `worker/manifest/suites.json`, validated by a JSON Schema. Clients pick only enum values and manifest keys (`suiteKey`, engine, fixture mode in local profile).
  - Strengths: no executable input surface; the Playwright command line is built by a typed argument builder with `shell: false`, and `--grep` is built from manifest tags only; suite revisions are hashes of checked-in files, so results are reproducible.
  - Weaknesses: adding a suite needs a commit; not useful as a general-purpose grid.
- **B. Uploaded spec files (zip/tarball) executed by workers.**
  - Rejected because: it is arbitrary code execution. Safe execution would need per-run VMs or gVisor/Firecracker-style isolation, egress filtering and resource accounting — a separate product, explicitly a MASTER non-goal.
- **C. Git-URL runners (clone a repo and run its tests).**
  - Rejected because: same RCE problem plus SSRF (the clone URL reaches internal hosts), supply-chain exposure from the cloned repo's dependencies, and credential handling for private repos.
- **D. Free-form `targetUrl` with a server-side allowlist regex.**
  - Rejected because: URL allowlists are bypass-prone (redirects, DNS rebinding, userinfo/encoding tricks), and a `baseUrlRef` key pointing to a worker-side setting removes the URL from the request entirely.

## Decision
- The only executable content is checked-in specs referenced by the manifest.
- Requests never carry URLs, file paths, repository links, shell fragments, JavaScript, regexes or Playwright flags (contracts §1). Unknown JSON fields are rejected on write endpoints.
- The allowlist is enforced in **both** the API (request validation, `422` problem types) and the worker (manifest + `baseUrlRef` resolution); hiding UI inputs is not a control.
- Fixture and chaos controls exist only in the local profile and are refused at startup elsewhere (CHAOS-004).

**What this does NOT claim:**
- It does not make TestGrid safe for untrusted *suite authors*: anyone who can commit to the manifest can run code. Repository access control is the boundary.
- It does not claim the browser itself is sandboxed beyond container limits; container hardening is PRD-12 and platform constraints are documented honestly.
- It does not claim parity with BrowserStack's multi-tenant isolation.

## Consequences
- **Benefits:** RCE and SSRF paths are removed by construction; threat-model review stays small; demo results are reproducible from suite revisions.
- **Risks:** pressure to "just add a URL field" for convenience — AGENTS safety guardrails forbid widening the surface silently; any change requires a new ADR plus threat model.
- **Invariants:** the worker's spawn path never interpolates client strings into argv.
- **Rollback / migration:** supporting user code later means superseding this ADR with a sandbox design.
- **Testing requirements:** API-003 (URL-like strings and unknown fields rejected), argument-builder unit tests, SEC-001, SEC-002 canary scan, CHAOS-004.

## Verification
- PRD-02: API-003.
- PRD-03: argument-builder and manifest-schema unit tests; token-redaction test.
- PRD-10: CHAOS-004. PRD-12: SEC-001, SEC-002, SEC-003.
