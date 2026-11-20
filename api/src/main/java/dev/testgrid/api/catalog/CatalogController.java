package dev.testgrid.api.catalog;

import dev.testgrid.api.catalog.CatalogResponses.Project;
import dev.testgrid.api.catalog.CatalogResponses.Suite;
import dev.testgrid.api.catalog.CatalogResponses.SuiteDetail;
import dev.testgrid.api.common.Page;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only projects and suites (PRD-02 FR-3). The lists are small and bounded by the manifest (at
 * most 50 suites), so they come back as one page with {@code nextCursor: null}.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

  private final CatalogRepository catalog;
  private final CallerScope scope;

  public CatalogController(CatalogRepository catalog, CallerScope scope) {
    this.catalog = catalog;
    this.scope = scope;
  }

  @GetMapping("/projects")
  public Page<Project> projects() {
    return new Page<>(catalog.findProject(scope.projectId()).stream().toList(), null);
  }

  @GetMapping("/projects/{projectId}/suites")
  public Page<Suite> suites(@PathVariable UUID projectId) {
    if (!scope.canSee(projectId) || catalog.findProject(projectId).isEmpty()) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }
    return new Page<>(catalog.currentSuites(projectId), null);
  }

  @GetMapping("/suites/{suiteId}")
  public SuiteDetail suite(@PathVariable UUID suiteId) {
    UUID projectId = scope.projectId();
    Suite suite =
        catalog
            .findSuite(projectId, suiteId)
            .orElseThrow(() ->
                new ApiProblemException(ProblemType.NOT_FOUND, "suite " + suiteId + " not found"));
    List<CatalogResponses.Case> cases = catalog.cases(projectId, suiteId);
    return new SuiteDetail(suite, cases);
  }
}
