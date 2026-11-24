package dev.testgrid.api.quarantine;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.Engine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One quarantine; {@code engine} null covers every engine. */
@Schema(name = "Quarantine")
public record QuarantineView(
    UUID id,
    UUID projectId,
    String caseKey,
    @ApiNullable Engine engine,
    String reason,
    QuarantineSource source,
    String createdBy,
    Instant createdAt,
    Instant expiresAt,
    @ApiNullable Instant revokedAt,
    QuarantineState state) {}
