package dev.testgrid.api.failure;

import dev.testgrid.api.problem.ProblemBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 failure-group endpoints (PRD-07 FR-2, FR-3). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "failures")
public class FailureGroupController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private final FailureGroupService groups;

  public FailureGroupController(FailureGroupService groups) {
    this.groups = groups;
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
