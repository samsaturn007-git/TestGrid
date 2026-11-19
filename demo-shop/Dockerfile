# syntax=docker/dockerfile:1
# Build context: repository root (needs the workspace lockfile).
FROM node:26.13.1-bookworm-slim AS base
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
# Node 26 images no longer bundle Corepack; pin the version used locally (docs/VERSIONS.md).
RUN npm install -g corepack@0.36.0 && corepack enable pnpm
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY demo-shop/package.json demo-shop/

FROM base AS build
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter demo-shop
COPY tsconfig.base.json ./
COPY demo-shop/ demo-shop/
RUN pnpm --filter demo-shop build

FROM base AS prod-deps
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --prod --filter demo-shop

FROM node:26.13.1-bookworm-slim
ENV NODE_ENV=production \
    DEMO_SHOP_HOST=0.0.0.0 \
    DEMO_SHOP_PORT=4173
WORKDIR /app
COPY --from=prod-deps /repo/node_modules ./node_modules
COPY --from=prod-deps /repo/demo-shop/node_modules ./demo-shop/node_modules
COPY demo-shop/package.json demo-shop/
COPY demo-shop/src/server demo-shop/src/server
COPY --from=build /repo/demo-shop/dist/client demo-shop/dist/client
WORKDIR /app/demo-shop
USER node
EXPOSE 4173
# Node 26 runs the TypeScript server directly (type stripping); there is no build step for it.
CMD ["node", "src/server/main.ts"]
