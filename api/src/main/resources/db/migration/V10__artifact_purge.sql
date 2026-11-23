-- PRD-06 FR-6: the purge job's second step reads EXPIRED rows (bytes still to delete). They are
-- few and short-lived, while PURGED rows accumulate, so a partial index keeps that read small.
CREATE INDEX artifacts_expired ON artifacts (id) WHERE state = 'EXPIRED';
