package dev.testgrid.api.quarantine;

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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 quarantine endpoints (PRD-08 FR-3). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "quality")
public class QuarantineController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private final QuarantineService quarantines;

  public QuarantineController(QuarantineService quarantines) {
    this.quarantines = quarantines;
  }

  @GetMapping(
      path = "/projects/{projectId}/quarantines",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "listQuarantines",
      summary = "Quarantines, newest first (at most 200), with the cap and suggestions",
      description =
          "state is computed from the database clock. suggestions lists FLAKY cases without an"
              + " active quarantine when the policy's autoMode is SUGGEST.")
  @ApiResponse(
      responseCode = "200",
      description = "The quarantines",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = QuarantineList.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public QuarantineList list(
      @PathVariable UUID projectId,
      @Parameter(description = "ACTIVE, EXPIRED or REVOKED; all when absent")
          @RequestParam(required = false)
          String state,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all) {
    List<FieldError> errors = new ArrayList<>();
    all.keySet().stream()
        .filter(name -> !name.equals("state"))
        .sorted()
        .forEach(name -> errors.add(new FieldError(name, "unknown query parameter")));
    if (all.containsKey("state") && all.get("state").size() > 1) {
      errors.add(new FieldError("state", "must appear once"));
    }
    QuarantineState parsed = null;
    if (state != null && !state.isEmpty()) {
      try {
        parsed = QuarantineState.valueOf(state);
      } catch (IllegalArgumentException e) {
        errors.add(new FieldError("state", "must be ACTIVE, EXPIRED or REVOKED"));
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the quarantine query is not valid", errors);
    }
    return quarantines.list(projectId, parsed);
  }

  @PostMapping(
      path = "/projects/{projectId}/quarantines",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createQuarantine",
      summary = "Quarantine a case, on one engine or all, with a reason and an expiry",
      description =
          "expiresAt must be in the future and at most 30 days ahead (database clock). Writes a"
              + " QUARANTINE_CREATED audit event. 409 quarantine-conflict when an active quarantine"
              + " already covers the case, or maxActive are active.")
  @ApiResponse(
      responseCode = "201",
      description = "The quarantine",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = QuarantineView.class)))
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "409",
      description = "quarantine-conflict",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ResponseEntity<QuarantineView> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateQuarantineRequest request) {
    QuarantineView created = quarantines.create(projectId, request);
    return ResponseEntity.created(URI.create("/api/v1/quarantines/" + created.id())).body(created);
  }

  @DeleteMapping(path = "/quarantines/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "revokeQuarantine",
      summary = "Revoke a quarantine now",
      description =
          "Writes a QUARANTINE_REVOKED audit event. The row is kept; revoking one already revoked"
              + " or expired changes nothing.")
  @ApiResponse(
      responseCode = "200",
      description = "The quarantine",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = QuarantineView.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public QuarantineView revoke(@PathVariable UUID id) {
    return quarantines.revoke(id);
  }
}
