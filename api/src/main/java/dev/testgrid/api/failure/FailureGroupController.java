package dev.testgrid.api.failure;

import dev.testgrid.api.common.Page;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.FailureCategory;
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
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 failure-group endpoints (PRD-07 FR-2, FR-3). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "failures")
public class FailureGroupController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private static final Set<String> LIST_PARAMS =
      Set.of("projectId", "category", "engine", "from", "to", "limit", "cursor");

  private final FailureGroupService groups;
  private final CallerScope scope;

  public FailureGroupController(FailureGroupService groups, CallerScope scope) {
    this.groups = groups;
    this.scope = scope;
  }

  @GetMapping(path = "/failure-groups", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "listFailureGroups",
      summary = "Failure groups, most occurrences first",
      description =
          "category matches the shown category (override, else rule) and may repeat or be"
              + " comma-separated; engine keeps groups with an occurrence on it; from (inclusive)"
              + " and to (exclusive) bound lastSeenAt. Keyset-paginated with nextCursor.")
  @ApiResponse(responseCode = "200", description = "A page of groups")
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (projectId outside the caller's scope)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public Page<FailureGroupSummary> list(
      @Parameter(description = "Defaults to the caller's project") @RequestParam(required = false)
          String projectId,
      @Parameter(description = "FailureCategory; repeat or comma-separate for several")
          @RequestParam(name = "category", required = false)
          List<String> category,
      @Parameter(description = "chromium, firefox or webkit") @RequestParam(required = false)
          String engine,
      @Parameter(description = "ISO-8601 instant, inclusive") @RequestParam(required = false)
          String from,
      @Parameter(description = "ISO-8601 instant, exclusive") @RequestParam(required = false)
          String to,
      @Parameter(description = "1-100, default 20") @RequestParam(required = false) String limit,
      @Parameter(description = "Opaque; from a previous page's nextCursor")
          @RequestParam(required = false)
          String cursor,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all) {
    List<FieldError> errors = new ArrayList<>();
    all.keySet().stream()
        .filter(name -> !LIST_PARAMS.contains(name))
        .sorted()
        .forEach(name -> errors.add(new FieldError(name, "unknown query parameter")));
    all.forEach((name, values) -> {
      if (LIST_PARAMS.contains(name) && !name.equals("category") && values.size() > 1) {
        errors.add(new FieldError(name, "must appear once"));
      }
    });
    UUID project = null;
    if (projectId != null && !projectId.isEmpty()) {
      try {
        project = UUID.fromString(projectId);
      } catch (IllegalArgumentException e) {
        errors.add(new FieldError("projectId", "must be a UUID"));
      }
    }
    Set<FailureCategory> categories = EnumSet.noneOf(FailureCategory.class);
    for (String value : category == null ? List.<String>of() : category) {
      for (String part : value.split(",", -1)) {
        if (part.isEmpty()) {
          continue;
        }
        try {
          categories.add(FailureCategory.valueOf(part));
        } catch (IllegalArgumentException e) {
          errors.add(new FieldError("category", part + " is not a FailureCategory"));
        }
      }
    }
    Engine parsedEngine = null;
    if (engine != null && !engine.isEmpty()) {
      if (engine.matches("^(chromium|firefox|webkit)$")) {
        parsedEngine = Engine.fromWireName(engine);
      } else {
        errors.add(new FieldError("engine", "must be chromium, firefox or webkit"));
      }
    }
    Instant fromAt = instant("from", from, errors);
    Instant toAt = instant("to", to, errors);
    if (fromAt != null && toAt != null && !fromAt.isBefore(toAt)) {
      errors.add(new FieldError("to", "must be after from"));
    }
    int parsedLimit = 20;
    if (limit != null) {
      if (limit.matches("^[0-9]{1,3}$")
          && Integer.parseInt(limit) >= 1
          && Integer.parseInt(limit) <= 100) {
        parsedLimit = Integer.parseInt(limit);
      } else {
        errors.add(new FieldError("limit", "must be 1-100"));
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the failure group query is not valid", errors);
    }
    if (project != null && !scope.canSee(project)) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + project + " not found");
    }
    FailureGroupCursor parsedCursor =
        cursor == null || cursor.isEmpty() ? null : FailureGroupCursor.decode(cursor);
    return groups.list(categories, parsedEngine, fromAt, toAt, parsedLimit, parsedCursor);
  }

  private static Instant instant(String name, String value, List<FieldError> errors) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      errors.add(new FieldError(name, "must be an ISO-8601 instant such as 2026-11-24T00:00:00Z"));
      return null;
    }
  }

  @GetMapping(path = "/failure-groups/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getFailureGroup",
      summary = "A failure group with its rule, evidence, occurrences and override",
      description =
          "category is the override when there is one, otherwise the rule's label (ruleCategory)."
              + " The label is likely, not proven: evidence says which rule matched and why.")
  @ApiResponse(
      responseCode = "200",
      description = "The group",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FailureGroupView.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public FailureGroupView get(@PathVariable UUID id) {
    return groups.get(id);
  }

  @PutMapping(
      path = "/failure-groups/{id}/override",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "overrideFailureGroup",
      summary = "Override a failure group's category, with a reason",
      description =
          "Stores who, why and when and writes an audit event in the same transaction. The"
              + " override stays in front of the rule's label for this fingerprint version."
              + " Repeating the current override changes nothing.")
  @ApiResponse(
      responseCode = "200",
      description = "The group with its override",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = FailureGroupView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public FailureGroupView override(
      @PathVariable UUID id, @Valid @RequestBody OverrideRequest request) {
    return groups.override(id, request);
  }
}
