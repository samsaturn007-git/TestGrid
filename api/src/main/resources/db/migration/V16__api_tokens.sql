-- PRD-08 FR-5 (contracts §3, CONFLICT_REGISTER N33): API tokens. Only the sha256 of a token is
-- stored; the raw value lives in the environment of whoever holds it. PRD-08 has one row, `ci`,
-- written at startup from TESTGRID_CI_TOKEN. PRD-12 adds user tokens, more scopes and POST /tokens.

CREATE TABLE api_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL UNIQUE CHECK (name ~ '^[a-z][a-z0-9-]{0,63}$'),
  token_hash   bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
  scopes       text[] NOT NULL CHECK (
                 cardinality(scopes) >= 1
                 AND scopes <@ ARRAY['runs:write', 'runs:read', 'quality:read']::text[]),
  expires_at   timestamptz NULL,
  last_used_at timestamptz NULL,
  revoked_at   timestamptz NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  version      int NOT NULL DEFAULT 1
);
