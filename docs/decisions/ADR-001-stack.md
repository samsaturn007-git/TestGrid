# ADR-001: Stack — Spring Boot API, Node Playwright worker, PostgreSQL as canonical state

**Date:** 2026-11-19 | **Status:** Accepted (developer sign-off 2026-11-19, recorded in `docs/evidence/PRD-00.md`)

## Context
- TestGrid needs an orchestrator that owns every state transition (runs, targets, attempts, leases, quality outcomes) and a worker that drives real browsers.
- The developer is strongest in React/TypeScript, Java/Spring Boot, Node and SQL, and wants a portfolio that shows backend depth for an SDE I role, not only frontend.
- Correctness depends on concurrency control: one active attempt per target (I1), immutable terminal states (I2), idempotent creation and completion. These are easiest to enforce *in the database* with constraints and conditional updates.
- Playwright is the browser engine. Its first-class runner (`@playwright/test`), reporters and sharding are Node-only.

## Options considered
- **A. Java 21 + Spring Boot API, Node/TypeScript Playwright worker, PostgreSQL (chosen).**
  - Strengths: strong transaction management, Spring Security, Flyway, springdoc-openapi and Testcontainers are mature; the worker uses Playwright's native runner and JSON reporter unchanged; PostgreSQL gives partial unique indexes, `FOR UPDATE SKIP LOCKED`, advisory locks, `LISTEN/NOTIFY` and `jsonb`.
  - Weaknesses: two languages and two build tools (Maven wrapper + pnpm); shared contracts need a generation step (OpenAPI → TS types, drift check in CI).
  - Ops cost: one JVM service plus Node workers; moderate memory for the JVM.
- **B. Node-only (API in Express/Fastify/NestJS + worker).**
  - Strengths: one language, one toolchain, shared types for free.
  - Rejected because: it hides the Java/Spring backend skill the portfolio is meant to show, and the Node ecosystem's transaction and locking story (ORM-dependent) makes the DB-enforced invariants less explicit to review. Shared types are achievable in A with generation.
- **C. Go API + Node worker.**
  - Strengths: small memory footprint, simple concurrency model, fast startup.
  - Rejected because: the developer is a Go beginner, so the hard parts (leases, idempotency, outbox) would be AI-written and not explainable in interview (MASTER risk "AI-generated code not understood"). It also adds a third language once the Node CLI/worker exist.
- **D. MongoDB (or another document store) as the source of truth.**
  - Strengths: flexible schema for run payloads and events.
  - Rejected because: the core invariants are relational — unique `(target_id, attempt_no)`, a partial unique index on active attempts, keyset pagination over `(queued_at, id)`, and multi-row transactions that update a target, insert case results, recompute the run and append an event atomically. Mongo can do transactions but cannot express the partial-unique "one active attempt" rule as simply, and the reviewer-readable SQL is part of the portfolio signal.

## Decision
- **API:** Java 21 (LTS), Spring Boot (exact version in `docs/VERSIONS.md`), Spring Security, Flyway, springdoc-openapi; built with the Maven wrapper.
- **Worker, CLI, chaos:** Node LTS + TypeScript with `@playwright/test`, zod, pino; pnpm workspace with a single lockfile.
- **Web:** React + TypeScript + Vite + Tailwind + TanStack Query + React Router; API types generated from OpenAPI.
- **Persistence:** PostgreSQL is the canonical record. Invariants are enforced with constraints and conditional updates first, and tested second.

**What this does NOT claim:**
- It does not claim Java is faster or "better" than Node or Go for this workload; no benchmark backs that, and none is planned.
- It does not claim PostgreSQL scales to a production multi-tenant grid; it is sized for a local, single-node demo.
- It does not claim the two-language split is free — the contract drift check exists precisely because it isn't.

## Consequences
- **Benefits:** invariants are visible in DDL and testable with Testcontainers; the worker stays a thin, replaceable executor; the stack maps directly to the developer's résumé.
- **Risks:** contract drift between Java DTOs and TS types (mitigated by the PRD-02 OpenAPI → TS drift check); JVM memory on a 16 GB laptop (mitigated by per-service limits in `docs/VERSIONS.md`).
- **Invariants:** the database, not any service's memory, is the source of truth (AGENTS engineering standards).
- **Rollback:** the worker talks to the API only over HTTP/AMQP contracts, so either side could be rewritten without touching the other.
- **Testing requirements:** Testcontainers PostgreSQL for every repository/transaction test; no H2 or in-memory substitutes for invariant tests.

## Verification
- PRD-01: `./mvnw verify` and `pnpm -r typecheck` both succeed in CI (AC-5).
- PRD-02: Testcontainers suite including API-004 (concurrent idempotent create) and API-006 (restart persistence).
- PRD-03: E2E-001 shows the Node worker reporting through the Java API into PostgreSQL.
