package dev.testgrid.api.internal;

import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactRejectedException;
import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.artifact.SingleFilePart;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contracts §7 {@code POST /internal/v1/attempts/{attemptId}/artifacts?kind=&caseKey=} (PRD-06
 * FR-3). The lease token travels in {@value #LEASE_HEADER}, never in the URL, and the body is a
 * {@code multipart/form-data} with one {@code file} part. Checks, in order, all before any byte is
 * stored: query ({@code 400}), content type ({@code 415}), declared length ({@code 413}), lease
 * ({@code 404 / 410 / 409}) and case key ({@code 400}); then, while streaming, size ({@code 413}),
 * magic bytes ({@code 415}) and multipart framing ({@code 400}).
 */
@RestController
@RequestMapping(path = "/internal/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class ArtifactUploadController {

  static final String LEASE_HEADER = "X-TestGrid-Lease-Token";
  // Room for the boundary lines and part headers on top of the kind's own limit.
  static final long MULTIPART_OVERHEAD = 16 * 1024;
  private static final Set<String> PARAMS = Set.of("kind", "caseKey");
  private static final Pattern CASE_KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
  private static final Pattern LEASE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{1,200}$");

  private final ArtifactUploadService uploads;

  ArtifactUploadController(ArtifactUploadService uploads) {
    this.uploads = uploads;
  }

  @PostMapping("/attempts/{attemptId}/artifacts")
  ResponseEntity<ArtifactView> upload(@PathVariable UUID attemptId, HttpServletRequest request)
      throws IOException {
    ArtifactKind kind = kind(request);
    String caseKey = request.getParameter("caseKey");
    String leaseToken = request.getHeader(LEASE_HEADER);
    if (leaseToken == null || !LEASE_TOKEN.matcher(leaseToken).matches()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "the lease token is required",
          List.of(new FieldError(LEASE_HEADER, "required")));
    }
    String boundary = SingleFilePart.boundary(request.getContentType());
    if (boundary == null) {
      throw new ApiProblemException(
          ProblemType.UNSUPPORTED_MEDIA_TYPE, "the body must be multipart/form-data");
    }
    if (request.getContentLengthLong() > kind.maxBytes() + MULTIPART_OVERHEAD) {
      throw new ApiProblemException(
          ProblemType.ARTIFACT_TOO_LARGE,
          "a " + kind + " artifact is at most " + kind.maxBytes() + " bytes");
    }
    try {
      ArtifactView view =
          uploads.upload(
              attemptId,
              leaseToken,
              kind,
              caseKey,
              () -> SingleFilePart.open(request.getInputStream(), boundary));
      return ResponseEntity.created(URI.create("/api/v1/artifacts/" + view.id())).body(view);
    } catch (ArtifactRejectedException e) {
      throw new ApiProblemException(e.type(), e.getMessage());
    }
  }

  /** Strict like the run list: an unknown or repeated parameter is refused, never ignored. */
  private static ArtifactKind kind(HttpServletRequest request) {
    List<FieldError> errors = new ArrayList<>();
    request
        .getParameterMap()
        .forEach((name, values) -> {
          if (!PARAMS.contains(name)) {
            errors.add(new FieldError(name, "unknown query parameter"));
          } else if (values.length != 1) {
            errors.add(new FieldError(name, "must be given once"));
          }
        });
    String caseKey = request.getParameter("caseKey");
    if (caseKey != null && (caseKey.length() > 64 || !CASE_KEY.matcher(caseKey).matches())) {
      errors.add(new FieldError("caseKey", "not a case key"));
    }
    ArtifactKind kind = null;
    String raw = request.getParameter("kind");
    try {
      kind = raw == null ? null : ArtifactKind.valueOf(raw);
    } catch (IllegalArgumentException e) {
      // reported below
    }
    if (kind == null) {
      errors.add(new FieldError("kind", "must be one of SCREENSHOT, TRACE, LOG"));
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the artifact upload query is invalid", errors);
    }
    return kind;
  }
}
