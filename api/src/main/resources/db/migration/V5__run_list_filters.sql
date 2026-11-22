-- PRD-05 T3: GET /runs filters. Each index keeps the keyset order (queued_at DESC, id DESC)
-- after its filter column, so a filtered page is still an index range scan with no sort.

-- Replaces runs_project_status (project_id, status): the new index has the same prefix, so
-- anything that used the old one can use this one.
CREATE INDEX runs_project_status_queued_at_id ON runs (project_id, status, queued_at DESC, id DESC);
DROP INDEX runs_project_status;

-- Most runs have no branch (manual runs), so only the rows that can match are indexed.
CREATE INDEX runs_project_branch_queued_at_id ON runs (project_id, branch, queued_at DESC, id DESC)
  WHERE branch IS NOT NULL;
