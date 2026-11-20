package dev.testgrid.api.problem;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Documentation-only shape of every error body (contracts §1, §6) for the OpenAPI spec. At runtime
 * the API writes Spring's {@code ProblemDetail}; {@code ProblemBodyShapeTest} keeps the two equal.
 */
@Schema(name = "Problem", description = "RFC 9457 Problem Details")
public record ProblemBody(
    @Schema(
            example = "https://testgrid.local/problems/validation-failed",
            description =
                "Absent (about:blank) only for HTTP errors outside the catalogue, e.g. 405")
        String type,
    String title,
    int status,
    String detail,
    String instance,
    @Schema(description = "W3C trace ID, 32 hex characters") String traceId,
    @Schema(description = "Only present when specific fields were wrong")
        List<FieldError> errors) {}
