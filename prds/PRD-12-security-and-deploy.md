# PRD-12 — Security hardening, observability stack, load baseline and deployable demo

**Priority:** P0 | **Estimated effort:** 16–24 h | **Dependencies:** PRD-11 | **Gate:** G4 | **Status:** not started

## Objective
Turn a working local system into a showcase you can defend and share:
- authenticated writes, hardened containers, and clean dependency and secret scans;
- end-to-end distributed tracing with metrics and dashboards;
- a measured load baseline;
- a safe, read-only public demo.

## User stories
- As a reviewer, I start the stack with `make up PROFILE=obs`, create a run, and follow one trace in Jaeger from the HTTP request through the queue to each Playwright case.
- As a reviewer, I open the Grafana dashboard and see queue wait, active sessions against the cap, attempt outcomes and outbox lag.
- As an anonymous visitor to the hosted demo, I can browse sample runs but can't execute anything.

## Interview signal
AuthN/AuthZ with scopes, container hardening, OpenTelemetry context propagation, RED/USE metrics, load testing method, threat modelling.

## Functional requirements
1. **FR-1 Authentication and scopes**
   - Extend `api_tokens` with personal tokens: `runs:read`, `runs:write`, `quarantine:write`, `policy:write`, `admin`.
   - Spring Security filter chains are separate for `/api` (bearer token) and `/internal` (service token).
   - Tokens are hashed and support expiry and revocation (`POST/DELETE /tokens`).
   - The UI uses a token from the local environment. With bearer-only auth and no cookies, CSRF does not apply; record that as an explicit decision.
2. **FR-2 API policies**
   - CORS limited to the web origin.
   - Per-token rate limits on `POST /runs` and the quarantine endpoints (bucket4j), returning `429` with `Retry-After`.
   - Request size limits, plus an audit of the validation on every endpoint.
3. **FR-3 Container hardening** (Compose)
   - Non-root users, `read_only: true` with `tmpfs` where possible, `cap_drop: [ALL]`, `security_opt: no-new-privileges`.
   - Memory, CPU and pids limits on every service.
   - Networks split into `edge` and `internal` (internal-only). No Docker socket anywhere.
   - Worker browsers follow Playwright's documented Docker guidance; any sandbox flags used are documented.
4. **FR-4 Scanning in CI**
   - gitleaks for secrets.
   - Trivy for images and the filesystem.
   - `pnpm audit` and OWASP dependency-check (Maven).
   - CodeQL if the repository is public.
   - High or critical findings fail CI unless there's a dated, justified exception in `SECURITY.md`.
5. **FR-5 Observability stack** (Compose profile `obs`)
   - OTel Java agent on the API and the OTel Node SDK on the worker, exporting to the OTel Collector, then to Jaeger (traces) and Prometheus (metrics).
   - Grafana is provisioned with a committed `testgrid.json` dashboard showing queue wait p50 and p95, active sessions against the cap, attempt outcomes per minute, outbox unpublished, reaper reclaims, DLQ depth and SSE clients.
   - Example Prometheus alert rules are included (no paging).
   - Trace context flows HTTP → outbox → AMQP → worker → Playwright child process (ARCHITECTURE §8).
   - A span-attribute redaction test: no tokens or cookies in span attributes.
6. **FR-6 Health and retention:** liveness and readiness endpoints that check DB, broker and store; log retention and rotation; a runbook in `docs/OPERATIONS.md`.
7. **FR-7 Load baseline**
   - k6 scripts for API create and list, and SSE fan-out with 100 clients.
   - An orchestration-throughput test using a `NoopExecutor` (worker test mode, which skips browsers) to measure dispatch → claim → complete overhead separately from browser cost.
   - Record the hardware and versions, and save the raw k6 JSON.
8. **FR-8 Read-only public demo:** a static build of the dashboard served with a recorded snapshot (`demo-data/*.json`) captured from real local runs and labelled "recorded sample". Execution controls are hidden and there is no backend. Deploy to GitHub Pages or a similar free static host.

## Tasks (in order)
- [ ] **T1** — Add scoped user tokens, separate filter chains, CORS and rate limits.
- [ ] **T2** — Harden Docker Compose (non-root, read-only, caps, limits, network split) and artifact storage isolation.
- [ ] **T3** — Add security/negative tests and CI scans (gitleaks, Trivy, dependency audit, CodeQL).
- [ ] **T4** — Add OpenTelemetry tracing end to end, Prometheus metrics, Grafana dashboard and alert rules.
- [ ] **T5** — Run k6 and NoopExecutor load baselines; document hardware and resource caps.
- [ ] **T6** — Build static read-only demo from recorded real data with hosting instructions.

## Acceptance criteria
- [ ] **AC-1:** Anonymous or under-scoped writes, artifact reads and quarantine changes are denied with `401` or `403` (SEC-001).
- [ ] **AC-2:** Unsafe URLs, path traversal, unknown fields and code-like inputs are rejected, and the secret scans and canary scan are clean (SEC-002, SEC-003).
- [ ] **AC-3:** Containers run as non-root with limits and no Docker socket, shown by `docker inspect` output in the evidence.
- [ ] **AC-4:** One trace in Jaeger spans API → dispatcher → AMQP → worker → Playwright cases with the same trace ID; the Grafana dashboard shows live data during a run (OBS-001, OBS-002).
- [ ] **AC-5:** Load results are reproducible and labelled with their environment, and the public demo can't run jobs because it has no execution endpoint.

## Verification
- Negative API tests
- The CI scan logs
- `docker inspect` checks
- A Jaeger screenshot and the trace ID
- Grafana screenshots
- `k6 run` outputs

## Demo moment
Start a run, then open its trace in Jaeger: one waterfall from the click to each browser case across four services.

## Risks
- Security of public execution (kept out of scope).
- Differences between Linux containers.
- The observability stack using too much RAM (it's an opt-in profile).

## Out of scope
Enterprise SSO, Kubernetes, claims of multi-tenant security, production compliance claims.

## Handoff and definition of done
- Implement **PRD-12 only**. Follow AGENTS.md: inspect the repository, list the files to change, implement T1–T6 with tests, then write `docs/evidence/PRD-12.md` with proof for every AC.
- The PRD is done only when:
  - every task is implemented, not simulated;
  - every AC has been checked against observable output;
  - real commands and their results are recorded, and anything skipped is noted;
  - security, failure modes, contracts and tests have been reviewed;
  - the developer can explain token scopes, trace propagation and the load method without AI;
  - the tracker is updated only after human review.
