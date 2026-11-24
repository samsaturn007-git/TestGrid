-- PRD-07 FR-7: GET /failure-groups pages by occurrence count, newest id first on ties.
CREATE INDEX failure_fingerprints_list
  ON failure_fingerprints (project_id, occurrence_count DESC, id DESC);
