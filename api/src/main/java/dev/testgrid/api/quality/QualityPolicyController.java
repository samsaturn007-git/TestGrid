package dev.testgrid.api.quality;

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
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
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

/** Contracts §4 quality-policy endpoints (PRD-08 FR-1). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "quality")
public class QualityPolicyController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private final QualityPolicyService policies;

  public QualityPolicyController(QualityPolicyService policies) {
    this.policies = policies;
  }

  @GetMapping(
      path = "/projects/{projectId}/quality-policy",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getQualityPolicy",
      summary = "The project's active quality policy, or one past version, with the history",
      description = "versions lists every version newest first (at most 100).")
  @ApiResponse(
      responseCode = "200",
      description = "The policy",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = QualityPolicyView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public QualityPolicyView get(
      @PathVariable UUID projectId,
      @Parameter(description = "A past version number; the active one when absent")
          @RequestParam(required = false)
          String version,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all) {
    List<FieldError> errors = new ArrayList<>();
    all.keySet().stream()
        .filter(name -> !name.equals("version"))
        .sorted()
        .forEach(name -> errors.add(new FieldError(name, "unknown query parameter")));
    if (all.containsKey("version") && all.get("version").size() > 1) {
      errors.add(new FieldError("version", "must appear once"));
    }
    Integer parsed = null;
    if (version != null) {
      if (version.matches("^[1-9][0-9]{0,8}$")) {
        parsed = Integer.parseInt(version);
      } else {
        errors.add(new FieldError("version", "must be a positive integer"));
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the quality policy query is not valid", errors);
    }
    return policies.get(projectId, parsed);
  }

  @PutMapping(
      path = "/projects/{projectId}/quality-policy",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "putQualityPolicy",
      summary = "Create the next policy version and make it active",
      description =
          "Never edits a version: the current one is deactivated and the next number is created,"
              + " with an audit event that carries the reason. Runs already evaluated keep the"
              + " version they were evaluated with.")
  @ApiResponse(
      responseCode = "200",
      description = "The new active version",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = QualityPolicyView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public QualityPolicyView put(
      @PathVariable UUID projectId, @Valid @RequestBody PolicyUpdateRequest request) {
    return policies.update(projectId, request);
  }
}
