package dev.testgrid.api.project;

import java.util.UUID;

/**
 * The single local project seeded by V2__local_project.sql (PRD-02 FR-10). Code still passes a
 * project ID into every query, so per-owner access can be added without rewriting them.
 */
public final class LocalProject {

  public static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  public static final String SLUG = "demo";

  private LocalProject() {}
}
