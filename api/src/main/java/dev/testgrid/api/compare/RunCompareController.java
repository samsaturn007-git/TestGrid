package dev.testgrid.api.compare;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemBody;
import dev.testgrid.api.problem.ProblemType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4/§8 {@code GET /runs/compare} (PRD-07 FR-6). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "runs")
public class RunCompareController {

  static final String LAST_GREEN = "last-green";
  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
  private static final Set<String> PARAMS = Set.of("base", "head");

  private final RunComparisonService comparisons;

  public RunCompareController(RunComparisonService comparisons) {
    this.comparisons = comparisons;
  }

  @GetMapping(path = "/runs/compare", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "compareRuns",
      summary = "What changed between two runs of one suite, per case and engine",
      description =
          "base=last-green picks the newest PASSED run of the same suite key on the same branch,"
              + " queued before head. Rows are ordered by category (NEW_FAILURE first), case and"
              + " engine.")
  @ApiResponse(
      responseCode = "200",
      description = "The comparison",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RunComparisonView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (a run, or no last green run)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "422",
      description = "incomparable-runs (different suite keys)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public RunComparisonView compare(
      @Parameter(description = "A run ID, or last-green") @RequestParam(required = false)
          String base,
      @Parameter(description = "A run ID") @RequestParam(required = false) String head,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all) {
    List<FieldError> errors = new ArrayList<>();
    all.keySet().stream()
        .filter(name -> !PARAMS.contains(name))
        .sorted()
        .forEach(name -> errors.add(new FieldError(name, "unknown query parameter")));
    all.forEach((name, values) -> {
      if (PARAMS.contains(name) && values.size() > 1) {
        errors.add(new FieldError(name, "must appear once"));
      }
    });
    UUID headId = uuid(head);
    if (headId == null) {
      errors.add(new FieldError("head", "required, a run ID"));
    }
    UUID baseId = LAST_GREEN.equals(base) ? null : uuid(base);
    if (!LAST_GREEN.equals(base) && baseId == null) {
      errors.add(new FieldError("base", "required, a run ID or last-green"));
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the comparison query is not valid", errors);
    }
    return comparisons.compare(baseId, headId);
  }

  private static UUID uuid(String value) {
    if (value == null
        || !value.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
      return null;
    }
    return UUID.fromString(value);
  }
}
