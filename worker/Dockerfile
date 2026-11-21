# syntax=docker/dockerfile:1
# Build context: repository root (needs the workspace lockfile and the manifest schema).
# Browsers come from the Playwright image whose tag equals the npm pin (docs/VERSIONS.md:
# "the Playwright npm version and Docker image tag must be identical").
FROM node:26.13.1-bookworm-slim AS deps
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN npm install -g corepack@0.36.0 && corepack enable pnpm
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY worker/package.json worker/
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --prod --filter worker

FROM mcr.microsoft.com/playwright:v1.65.1-noble
# This image already ships Node v26.13.1, the repo's pin (checked 2026-11-20 with
# `node --version` in the image); the worker runs its TypeScript with Node's type stripping.
ENV NODE_ENV=production \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
WORKDIR /app
COPY --from=deps /repo/node_modules ./node_modules
COPY --from=deps /repo/worker/node_modules ./worker/node_modules
COPY packages/contracts/schema packages/contracts/schema
COPY worker/package.json worker/playwright.config.ts worker/
COPY worker/manifest worker/manifest
COPY worker/suites worker/suites
COPY worker/src worker/src
WORKDIR /app/worker
# Non-root: the Playwright image's own unprivileged user.
USER pwuser
CMD ["node", "src/main.ts"]
