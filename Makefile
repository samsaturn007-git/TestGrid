# TestGrid root commands (PRD-01 FR-2). Written for GNU Make 3.81 (macOS default).
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

# The repo needs JDK 21; on the reference Mac the default `java` is 26 (CLAUDE.md §3).
# Use Homebrew's openjdk@21 when it exists; elsewhere (CI, Linux) keep JAVA_HOME as set.
BREW_JDK21 := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
ifneq ($(wildcard $(BREW_JDK21)),)
export JAVA_HOME := $(BREW_JDK21)
endif

COMPOSE := docker compose -f infra/compose/compose.yaml --env-file .env
MVNW := cd api && ./mvnw -B -q
FIXTURE_MODE ?= PASS
FIXTURE_SEED ?= 42
# The spec support reads the worker → Playwright names (PRD-03 FR-5, CONFLICT_REGISTER N8).
export TG_FIXTURE_MODE := $(FIXTURE_MODE)
export TG_FIXTURE_SEED := $(FIXTURE_SEED)

.PHONY: help env install up down reset logs ps check format lint typecheck test test-node test-api contracts contracts-check restart-check smoke fixture-checks modes e2e dashboard-e2e kill-check crash-check eval-failures stuck-runs dlq dlq-replay sigterm-check scan-canary doctor health clean

help: ## List the available commands
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

env:
	@test -f .env || { cp .env.example .env; echo "created .env from .env.example (fake local values)"; }

install: ## Install Node dependencies from the lockfile
	pnpm install --frozen-lockfile

up: env ## Build and start postgres, api, web and demo-shop; wait until healthy
	@start=$$(date +%s); \
	$(COMPOSE) up -d --build --wait --wait-timeout 180; \
	echo "all services healthy in $$(( $$(date +%s) - start ))s"
	@bash scripts/health.sh

down: ## Stop the environment (keeps the database volume)
	$(COMPOSE) down --remove-orphans

reset: env ## Stop, delete volumes and start again from a clean state
	$(COMPOSE) down --volumes --remove-orphans
	@$(MAKE) --no-print-directory up

logs: ## Follow service logs
	$(COMPOSE) logs -f --tail=200

ps: ## Show service status and health
	$(COMPOSE) ps

check: ## Format check, lint and typecheck (TypeScript and Java)
	pnpm format:check
	pnpm lint
	pnpm typecheck
	pnpm exec tsc -p scripts/tsconfig.json
	$(MVNW) spotless:check compile

format: ## Apply Prettier and Spotless formatting
	pnpm format
	$(MVNW) spotless:apply

test: test-node test-api ## Unit and integration tests (no Docker needed)

test-node:
	pnpm test

test-api:
	$(MVNW) verify

contracts: ## Regenerate api/openapi.yaml (springdoc) and packages/contracts/src/api.ts (needs Docker)
	$(MVNW) test -Dtest=OpenApiSpecTest -Dtestgrid.openapi.write=true -Dsurefire.failIfNoSpecifiedTests=false
	pnpm --filter @testgrid/contracts generate

contracts-check: contracts ## Fail if the committed contract files differ from the code (PRD-02 FR-9)
	@bash scripts/contracts-check.sh

restart-check: ## Create a run, restart the api container, check it reads back unchanged (PRD-02 AC-1)
	@bash scripts/restart-check.sh

smoke: ## Health checks plus every registered suite on Chromium (FIXTURE_MODE=PASS by default)
	@bash scripts/health.sh
	pnpm --filter worker exec playwright test --project=chromium

fixture-checks: ## Browser-level fixture isolation check (AC-3)
	pnpm --filter worker exec playwright test --project=fixture-checks

modes: ## Run every fixture mode 3 times after a reset and compare results (AC-2)
	bash scripts/mode-matrix.sh

e2e: ## Submit runs through the API and check the worker's real results (PRD-03; ENGINES=chromium)
	node scripts/e2e.mjs $(or $(ENGINES),chromium)

dashboard-e2e: env ## Dashboard Playwright E2E and real-browser axe; the worker serves all engines (PRD-05 T7)
	TESTGRID_WORKER_ENGINES=chromium,firefox,webkit $(MAKE) up
	pnpm --filter web e2e

kill-check: ## kill -9 the worker mid-run; attempt 1 ABANDONED and kept, attempt 2 runs within the bound (PRD-04 AC-4)
	@bash scripts/kill-check.sh

crash-check: ## Real worker with TG_FAULT after_complete_before_ack and after_claim (PRD-04 AC-3, AC-4)
	@bash scripts/crash-check.sh

eval-failures: ## Failure classifier vs labelled scenarios; needs a clean stack (make reset, then make up). PRD-07 FR-8
	node scripts/eval-failures.ts $(EVAL_ARGS)

stuck-runs: ## Targets that should have moved but did not (I8; docs/RUNBOOK.md §3). Read-only
	@bash scripts/stuck-runs.sh

dlq: ## List the dead-letter queue with each target's database state (docs/RUNBOOK.md §4)
	@bash scripts/dlq.sh list

dlq-replay: ## Put every dead letter back on its engine queue; the claim fences stale ones
	@bash scripts/dlq.sh replay

sigterm-check: ## SIGTERM the worker mid-run; grace period, then INFRA_ERROR/WORKER_SHUTDOWN (PRD-03 FR-6)
	@bash scripts/sigterm-check.sh

scan-canary: env ## Canary secret in worker, API and page; scan everything stored or served for it (PRD-06 AC-4)
	@bash scripts/scan-canary.sh

doctor: ## Check the local toolchain against docs/VERSIONS.md
	@bash scripts/doctor.sh

health: ## curl every service health endpoint
	@bash scripts/health.sh

clean: ## Remove build output and test reports
	rm -rf demo-shop/dist web/dist worker/test-results worker/playwright-report api/target
