package dev.testgrid.api.run;

import dev.testgrid.api.catalog.CatalogRepository;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemBody;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD-05 FR-2: the Overview's "status counts for the last 24 h", counted by the database so every
 * number on the page comes from the API (CONFLICT_REGISTER N19). A run counts when it was queued
 * inside the window, under its current status.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "runs")
public class RunCountsController {

  static final int MAX_HOURS = 168;

  /** One entry per RunStatus, in contracts §2.1 order, zeros included. */
  public record StatusCount(RunStatus status, int count) {}

  /** {@code from} and {@code to} are the database clock's window edges. */
  public record RunCounts(
      Instant from, Instant to, int hours, int total, List<StatusCount> items) {}

  private final JdbcClient jdbc;
  private final CallerScope scope;
  private final CatalogRepository catalog;

  public RunCountsController(JdbcClient jdbc, CallerScope scope, CatalogRepository catalog) {
    this.jdbc = jdbc;
    this.scope = scope;
    this.catalog = catalog;
  }

  @GetMapping("/projects/{projectId}/run-counts")
  @Operation(
      operationId = "countRuns",
      summary = "Runs queued in the last N hours, by current status",
      description = "Every RunStatus is listed, zeros included, in the contract's order.")
  @ApiResponse(responseCode = "200", description = "The counts")
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed (hours outside 1-168)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemBody.class)))
  @Transactional(readOnly = true)
  public RunCounts counts(
      @PathVariable UUID projectId,
      @Parameter(description = "1-168, default 24") @RequestParam(required = false) Integer hours) {
    int window = hours == null ? 24 : hours;
    if (window < 1 || window > MAX_HOURS) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "hours must be between 1 and " + MAX_HOURS,
          List.of(new FieldError("hours", "must be between 1 and " + MAX_HOURS)));
    }
    if (!scope.canSee(projectId) || catalog.findProject(projectId).isEmpty()) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }

    // One statement, so the window and the counts come from the same instant (now() is fixed for
    // the transaction). The keyset index runs(project_id, queued_at DESC, id DESC) serves it.
    Map<String, Object> edges =
        jdbc.sql("SELECT now() AS to_ts, now() - make_interval(hours => :h) AS from_ts")
            .param("h", window)
            .query()
            .singleRow();
    Instant to = ((Timestamp) edges.get("to_ts")).toInstant();
    Instant from = ((Timestamp) edges.get("from_ts")).toInstant();
    Map<String, Integer> byStatus = new HashMap<>();
    jdbc.sql(
            """
            SELECT status, count(*)::int AS n
            FROM runs
            WHERE project_id = :p AND queued_at >= now() - make_interval(hours => :h)
            GROUP BY status
            """)
        .param("p", projectId)
        .param("h", window)
        .query(rs -> {
          byStatus.put(rs.getString("status"), rs.getInt("n"));
        });
    List<StatusCount> items =
        Arrays.stream(RunStatus.values())
            .map(s -> new StatusCount(s, byStatus.getOrDefault(s.name(), 0)))
            .toList();
    int total = items.stream().mapToInt(StatusCount::count).sum();
    return new RunCounts(from, to, window, total, items);
  }
}
