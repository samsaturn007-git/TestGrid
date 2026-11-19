# Architecture Decision Records

Each ADR records one decision, the options that were rejected and why, and what the decision does **not** claim. Format: [`ADR_TEMPLATE.md`](ADR_TEMPLATE.md). Write a new ADR whenever ARCHITECTURE §12 says so; never edit an accepted ADR's decision — supersede it with a new one.

| ADR | Title | Status | Date | Verified by |
|---|---|---|---|---|
| [ADR-001](ADR-001-stack.md) | Stack: Spring Boot API, Node Playwright worker, PostgreSQL as canonical state | Proposed | 2026-11-19 | PRD-01 builds, PRD-02 Testcontainers suite |
| [ADR-002](ADR-002-phased-delivery.md) | Phased delivery: single polling worker first, then RabbitMQ with a transactional outbox | Proposed | 2026-11-19 | E2E-001 (PRD-03), Q-001…Q-009 (PRD-04) |
| [ADR-003](ADR-003-safe-execution-boundary.md) | Safe execution boundary: manifest-only suites, no client URLs or code | Proposed | 2026-11-19 | API-003 (PRD-02), SEC-001/002 (PRD-12) |
| [ADR-004](ADR-004-dispatch-ownership.md) | Dispatch and scheduling ownership: the DB dispatcher decides, the broker transports | Proposed | 2026-11-19 | Q-001, Q-002 (PRD-04), SCHED-001 (PRD-11) |

**Status values:** `Proposed` → `Accepted` → `Superseded by ADR-NNN`.
