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
