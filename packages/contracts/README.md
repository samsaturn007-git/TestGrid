# @testgrid/contracts

Shared contracts between the API, the worker and the web app.

- `src/api.ts`: TypeScript types generated from `api/openapi.yaml` by openapi-typescript (PRD-02 FR-9). Import with `import type { components, paths } from '@testgrid/contracts'`. Never edit it by hand.
- `schema/suite-manifest.schema.json`: JSON Schema for `worker/manifest/suites.json` (contracts §10). The API bundles it into its jar and validates the manifest at startup (PRD-02 FR-2); the worker loader uses the same file from PRD-03.

Both generated files are committed. After changing an API DTO or endpoint:

```bash
make contracts         # regenerate api/openapi.yaml and src/api.ts (needs Docker for Testcontainers)
make contracts-check   # what CI runs: regenerate, then fail if anything differs from git
```

The `FixtureMode` list (contracts §2.1) is still declared in `demo-shop/src/server/fixture-mode.ts` and mirrored in `worker/suites/support/fixture-env.ts`.
