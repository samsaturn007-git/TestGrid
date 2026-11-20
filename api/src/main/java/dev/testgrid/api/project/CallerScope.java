package dev.testgrid.api.project;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ownership seam (PRD-02 FR-10): the one place that decides which project a request may see.
 * Until authentication exists every caller is bound to the local project; every query takes the
 * project ID from here, so access control later replaces this class rather than the queries.
 */
@Component
public class CallerScope {

  public UUID projectId() {
    return LocalProject.ID;
  }

  public boolean canSee(UUID projectId) {
    return projectId().equals(projectId);
  }
}
