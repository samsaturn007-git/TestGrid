package dev.testgrid.api.quality;

import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Serves a run's quality evaluation, evaluating a finished run on demand if it has none yet. */
@Service
public class RunQualityService {

  private final JdbcClient jdbc;
  private final CallerScope scope;
  private final QualityEvaluator evaluator;

  public RunQualityService(JdbcClient jdbc, CallerScope scope, QualityEvaluator evaluator) {
    this.jdbc = jdbc;
    this.scope = scope;
    this.evaluator = evaluator;
  }

  public RunQualityView quality(UUID runId) {
    RunStatus status =
        jdbc.sql("SELECT status FROM runs WHERE id = :r AND project_id = :p")
            .param("r", runId)
            .param("p", scope.projectId())
            .query((rs, i) -> RunStatus.valueOf(rs.getString("status")))
            .optional()
            .orElseThrow(() ->
                new ApiProblemException(ProblemType.NOT_FOUND, "run " + runId + " not found"));
    return evaluator
        .evaluate(runId)
        .map(e ->
            new RunQualityView(
                runId,
                status,
                e.id(),
                e.outcome(),
                new RunQualityView.PolicyRef(e.policyId(), e.policyVersion()),
                e.evaluatedAt(),
                e.rules()))
        .orElseGet(() -> new RunQualityView(runId, status, null, null, null, null, List.of()));
  }
}
