package dev.testgrid.api.run;

import dev.testgrid.api.common.Page;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

  // Same rules as CreateRunRequest, so a filter can only hold a value a run could have.
  private static final Pattern SUITE_KEY = Pattern.compile(CreateRunRequest.KEY);
  private static final Pattern BRANCH = Pattern.compile("^(?!.*\\.\\.)[A-Za-z0-9._/-]{1,200}$");

  @Transactional(readOnly = true)
  public Page<RunSummary> list(
      Integer limit,
      String cursor,
      UUID projectId,
      List<RunStatus> statuses,
      String suiteKey,
      String branch,
      List<Trigger> triggers) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    // An empty value (?branch=) means "no filter", the same as leaving the parameter out.
    suiteKey = blankToNull(suiteKey);
    branch = blankToNull(branch);
    List<FieldError> errors = new ArrayList<>();
    if (size < 1 || size > MAX_LIMIT) {
      errors.add(new FieldError("limit", "must be between 1 and " + MAX_LIMIT));
    }
    if (suiteKey != null && (suiteKey.length() > 64 || !SUITE_KEY.matcher(suiteKey).matches())) {
      errors.add(new FieldError("suiteKey", CreateRunRequest.MSG_KEY));
    }
    if (branch != null && !BRANCH.matcher(branch).matches()) {
      errors.add(new FieldError("branch", "must be a branch name (A-Z a-z 0-9 . _ / -)"));
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the run list query has invalid parameters", errors);
    }
    // A project outside the caller's scope answers 404, exactly like one that doesn't exist.
    if (projectId != null && !scope.canSee(projectId)) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }
    RunCursor after = cursor == null || cursor.isEmpty() ? null : RunCursor.decode(cursor);
    RunFilter filter =
        new RunFilter(
            present(statuses), blankToNull(suiteKey), blankToNull(branch), present(triggers));

    // One extra row tells us whether another page exists without a count(*) query.
    List<RunSummary> rows = runs.listRuns(scope.projectId(), filter, after, size + 1);
    if (rows.size() <= size) {
      return new Page<>(rows, null);
    }
    List<RunSummary> page = rows.subList(0, size);
    RunSummary last = page.getLast();
    return new Page<>(List.copyOf(page), new RunCursor(last.queuedAt(), last.id()).encode());
  }

  /**
   * {@code ?status=} (empty) binds as a null element; it means "no filter", like leaving it out.
   */
  private static <T> Set<T> present(List<T> values) {
    Set<T> set = new HashSet<>();
    if (values != null) {
      for (T value : values) {
        if (value != null) {
          set.add(value);
        }
      }
    }
    return set;
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
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
