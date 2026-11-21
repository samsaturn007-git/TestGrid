package dev.testgrid.api.run;

import dev.testgrid.api.common.Page;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET /runs and GET /runs/{runId} (PRD-02 FR-6, FR-7), always inside the caller's project. */
@Service
public class RunQueryService {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private final RunRepository runs;
  private final CallerScope scope;

  public RunQueryService(RunRepository runs, CallerScope scope) {
    this.runs = runs;
    this.scope = scope;
  }

  @Transactional(readOnly = true)
  public Page<RunSummary> list(Integer limit, String cursor) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "limit must be between 1 and " + MAX_LIMIT,
          List.of(new FieldError("limit", "must be between 1 and " + MAX_LIMIT)));
    }
    RunCursor after = cursor == null || cursor.isEmpty() ? null : RunCursor.decode(cursor);

    // One extra row tells us whether another page exists without a count(*) query.
    List<RunSummary> rows = runs.listRuns(scope.projectId(), after, size + 1);
    if (rows.size() <= size) {
      return new Page<>(rows, null);
    }
    List<RunSummary> page = rows.subList(0, size);
    RunSummary last = page.getLast();
    return new Page<>(List.copyOf(page), new RunCursor(last.queuedAt(), last.id()).encode());
  }

  /** FR-9: same scope rule as {@link #get}; one snapshot for the run and its results. */
  @Transactional(readOnly = true)
  public RunResults results(UUID runId) {
    RunResponse run = get(runId);
    return new RunResults(run.id(), run.status(), runs.findResults(run.id(), run.targets()));
  }

  @Transactional(readOnly = true)
  public RunResponse get(UUID runId) {
    return runs.findRun(scope.projectId(), runId)
        .orElseThrow(
            () -> new ApiProblemException(ProblemType.NOT_FOUND, "run " + runId + " not found"));
  }
}
