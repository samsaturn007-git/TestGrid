package dev.testgrid.api.casehealth;

import dev.testgrid.api.domain.Engine;
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
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 {@code GET /cases/{caseKey}/history} (PRD-07 FR-5). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "cases")
public class CaseHistoryController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
  private static final Set<String> PARAMS = Set.of("suiteKey", "engine", "limit");
  private static final String KEY = "^[a-z0-9]+(-[a-z0-9]+)*$";
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  private final CaseHistoryService history;

  public CaseHistoryController(CaseHistoryService history) {
    this.history = history;
  }

  @GetMapping(path = "/cases/{caseKey}/history", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getCaseHistory",
      summary = "A case's recent results and health label per engine",
      description =
          "Results are newest first. health is the rolling window (FR-4) with its label and"
              + " reason, or null before the case's first result on that engine. Without engine,"
              + " every engine the suite allows is listed.")
  @ApiResponse(
      responseCode = "200",
      description = "The history",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = CaseHistoryView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed (a key, engine, limit or an unknown parameter)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (no revision of the suite has this case)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public CaseHistoryView get(
      @PathVariable String caseKey,
      @Parameter(description = "The suite the case belongs to") @RequestParam(required = false)
          String suiteKey,
      @Parameter(description = "chromium, firefox or webkit; every allowed engine if absent")
          @RequestParam(required = false)
          String engine,
      @Parameter(description = "Results per engine, 1-100, default 20")
          @RequestParam(required = false)
          String limit,
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
    if (!validKey(caseKey)) {
      errors.add(new FieldError("caseKey", "must be a case key"));
    }
    if (suiteKey == null || !validKey(suiteKey)) {
      errors.add(new FieldError("suiteKey", "required, a suite key"));
    }
    Engine parsedEngine = null;
    if (engine != null && !engine.isEmpty()) {
      if (!engine.matches("^(chromium|firefox|webkit)$")) {
        errors.add(new FieldError("engine", "must be chromium, firefox or webkit"));
      } else {
        parsedEngine = Engine.fromWireName(engine);
      }
    }
    int parsedLimit = DEFAULT_LIMIT;
    if (limit != null) {
      if (!limit.matches("^[0-9]{1,3}$")
          || Integer.parseInt(limit) < 1
          || Integer.parseInt(limit) > MAX_LIMIT) {
        errors.add(new FieldError("limit", "must be 1-" + MAX_LIMIT));
      } else {
        parsedLimit = Integer.parseInt(limit);
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the case history query is not valid", errors);
    }
    return history.history(caseKey, suiteKey, parsedEngine, parsedLimit);
  }

  private static boolean validKey(String key) {
    return key.length() <= 64 && key.matches(KEY);
  }
}
