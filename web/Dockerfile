# syntax=docker/dockerfile:1
# Build context: repository root (needs the workspace lockfile).
FROM node:26.13.1-bookworm-slim AS build
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN npm install -g corepack@0.36.0 && corepack enable pnpm
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY web/package.json web/
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter web
COPY tsconfig.base.json ./
COPY web/ web/
RUN pnpm --filter web build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/web/dist /usr/share/nginx/html
EXPOSE 8080
