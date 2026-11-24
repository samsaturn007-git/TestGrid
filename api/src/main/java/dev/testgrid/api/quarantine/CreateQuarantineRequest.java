package dev.testgrid.api.quarantine;

import dev.testgrid.api.domain.Engine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/quarantines} (PRD-08 FR-3). {@code engine} null
 * quarantines the case on every engine. {@code source} is MANUAL (the default) or SUGGESTED, when
 * the dashboard's "Quarantine?" prompt sent it.
 */
public record CreateQuarantineRequest(
    @NotNull @Size(max = 64) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String caseKey,
    Engine engine,
    @NotBlank @Size(max = 500) String reason,
    @NotNull Instant expiresAt,
    QuarantineSource source) {}
