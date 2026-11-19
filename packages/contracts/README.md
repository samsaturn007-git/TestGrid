# packages/contracts

Shared contracts between the API, the worker and the web app.

- `schema/suite-manifest.schema.json`: JSON Schema for `worker/manifest/suites.json` (contracts §10). The API bundles it into its jar and validates the manifest at startup (PRD-02 FR-2); the worker loader uses the same file from PRD-03.
- Generated OpenAPI types (`src/api.ts`) arrive in PRD-02 T5.

The `FixtureMode` list (contracts §2.1) is still declared in `demo-shop/src/server/fixture-mode.ts` and mirrored in `worker/suites/support/fixture-env.ts`.
