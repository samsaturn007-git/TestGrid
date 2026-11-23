package dev.testgrid.api.artifact;

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
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contracts §4 artifact endpoints (PRD-06 FR-4, ADR-005). */
@RestController
@RequestMapping(path = "/api/v1")
@Tag(name = "artifacts")
public class ArtifactController {

  private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
  private static final Set<String> CONTENT_PARAMS = Set.of("expires", "sig");

  /**
   * The bytes come from a browser under test, so the response must never be interpreted as a page:
   * download rather than render on navigation, no type sniffing, a CSP that allows nothing to run,
   * not cacheable, not readable cross-origin, and the signed URL is never sent on as a referrer.
   */
  static final String CSP = "default-src 'none'; img-src 'self'; sandbox";

  private final ArtifactService artifacts;

  public ArtifactController(ArtifactService artifacts) {
    this.artifacts = artifacts;
  }

  @GetMapping(path = "/artifacts/{artifactId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getArtifact",
      summary = "Artifact metadata",
      description =
          "state is EXPIRED once expires_at has passed, even before the hourly purge marks the"
              + " row. The storage key is never returned.")
  @ApiResponse(
      responseCode = "200",
      description = "The artifact",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ArtifactView.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ArtifactView get(@PathVariable UUID artifactId) {
    return artifacts.metadata(artifactId);
  }

  @PostMapping(path = "/artifacts/{artifactId}/links", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createArtifactLink",
      summary = "A signed content link, valid for at most 5 minutes",
      description =
          "url is relative to the API origin and carries expires (epoch seconds) and sig"
              + " (HMAC-SHA256 of the artifact ID and expiry). It is the only way to read the"
              + " content. Not access control: until PRD-12 anyone who reaches the API can ask.")
  @ApiResponse(
      responseCode = "200",
      description = "The link",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ArtifactService.Link.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found (unknown, or the bytes are MISSING)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "410",
      description = "artifact-expired",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ArtifactService.Link link(@PathVariable UUID artifactId) {
    return artifacts.link(artifactId);
  }

  @GetMapping("/artifacts/{artifactId}/content")
  @Operation(
      operationId = "getArtifactContent",
      summary = "Stream the bytes through a signed link",
      description =
          "Content-Disposition: attachment, X-Content-Type-Options: nosniff, a CSP that allows"
              + " nothing to run, Cache-Control: private, no-store. An <img> still shows a"
              + " screenshot; navigating to the URL downloads it.")
  @ApiResponse(
      responseCode = "200",
      description = "The bytes, with the artifact's stored type",
      content = {
        @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
        @Content(
            mediaType = "application/zip",
            schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "text/plain", schema = @Schema(type = "string"))
      })
  @ApiResponse(
      responseCode = "400",
      description = "validation-failed (unknown query parameter)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "403",
      description = "forbidden (missing, forged or expired link)",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "404",
      description = "not-found",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  @ApiResponse(
      responseCode = "410",
      description = "artifact-expired",
      content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemBody.class)))
  public ResponseEntity<InputStreamResource> content(
      @PathVariable UUID artifactId,
      @Parameter(description = "Epoch seconds, from the link") @RequestParam(required = false)
          String expires,
      @Parameter(description = "Signature, from the link") @RequestParam(required = false)
          String sig,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> all)
      throws IOException {
    List<FieldError> unknown =
        all.keySet().stream()
            .filter(name -> !CONTENT_PARAMS.contains(name))
            .sorted()
            .map(name -> new FieldError(name, "unknown query parameter"))
            .toList();
    if (!unknown.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the content link has unknown parameters", unknown);
    }
    ArtifactService.Content content = artifacts.open(artifactId, expires, sig);
    ArtifactView view = content.artifact();
    String filename =
        view.kind().name().toLowerCase(Locale.ROOT)
            + "-"
            + view.id()
            + "."
            + view.kind().extension();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(view.mime()))
        .contentLength(view.byteSize())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", CSP)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("Cross-Origin-Resource-Policy", "same-origin")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Artifact-Sha256", view.sha256())
        .body(new InputStreamResource(content.bytes()));
  }
}
