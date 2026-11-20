-- PRD-02 FR-10: one fixed local project. The ID is fixed so local scripts and demos can
-- use it. Authentication and per-owner access arrive later; until then every query is
-- still scoped by project_id.
INSERT INTO projects (id, slug, name, owner_id)
VALUES ('00000000-0000-4000-8000-000000000001', 'demo', 'Demo shop', NULL);
