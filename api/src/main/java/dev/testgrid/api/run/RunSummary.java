package dev.testgrid.api.run;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.RunStatus;
import dev.testgrid.api.domain.Trigger;
import java.time.Instant;
import java.util.UUID;

/** One row of GET /runs. {@code status} is the stored aggregate (contracts §2.3). */
public record RunSummary(
    UUID id,
    UUID projectId,
    String suiteKey,
    String suiteRevision,
    RunStatus status,
    Trigger trigger,
    @ApiNullable CreateRunRequest.Fixture fixture,
    @ApiNullable CreateRunRequest.Source source,
    int targetCount,
    Instant queuedAt,
    @ApiNullable Instant startedAt,
    @ApiNullable Instant finishedAt,
    RunResponse.Links links) {}
