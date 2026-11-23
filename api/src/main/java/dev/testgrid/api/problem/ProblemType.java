package dev.testgrid.api.problem;

import java.net.URI;
import org.springframework.http.HttpStatus;

/** Contracts §6. Only the types the API can produce so far; later PRDs add theirs. */
public enum ProblemType {
  VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request validation failed"),
  UNKNOWN_SUITE("unknown-suite", HttpStatus.UNPROCESSABLE_CONTENT, "Unknown suite"),
  ENGINE_NOT_ALLOWED("engine-not-allowed", HttpStatus.UNPROCESSABLE_CONTENT, "Engine not allowed"),
  FIXTURE_NOT_ALLOWED(
      "fixture-not-allowed", HttpStatus.UNPROCESSABLE_CONTENT, "Fixture mode not allowed"),
  IDEMPOTENCY_CONFLICT(
      "idempotency-conflict", HttpStatus.CONFLICT, "Idempotency key reused with a different body"),
  NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Not found"),
  UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Missing or invalid credentials"),
  ATTEMPT_NOT_ACTIVE("attempt-not-active", HttpStatus.CONFLICT, "Attempt is not active"),
  LEASE_EXPIRED("lease-expired", HttpStatus.GONE, "Lease expired or replaced"),
  STALE_DISPATCH("stale-dispatch", HttpStatus.CONFLICT, "Dispatch message is stale"),
  CAPACITY_EXHAUSTED(
      "capacity-exhausted", HttpStatus.TOO_MANY_REQUESTS, "Global session cap reached"),
  INVALID_COMPLETION(
      "invalid-completion",
      HttpStatus.UNPROCESSABLE_CONTENT,
      "Completion does not match the claim"),
  RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
  INVALID_RERUN("invalid-rerun", HttpStatus.UNPROCESSABLE_CONTENT, "Run cannot be rerun as asked"),
  ARTIFACT_TOO_LARGE("artifact-too-large", HttpStatus.CONTENT_TOO_LARGE, "Artifact too large"),
  UNSUPPORTED_MEDIA_TYPE(
      "unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");

  private static final String BASE = "https://testgrid.local/problems/";

  private final String slug;
  private final HttpStatus status;
  private final String title;

  ProblemType(String slug, HttpStatus status, String title) {
    this.slug = slug;
    this.status = status;
    this.title = title;
  }

  public URI uri() {
    return URI.create(BASE + slug);
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
