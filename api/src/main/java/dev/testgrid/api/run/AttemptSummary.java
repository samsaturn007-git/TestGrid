package dev.testgrid.api.run;

import dev.testgrid.api.common.ApiNullable;
import dev.testgrid.api.domain.AttemptStatus;
import dev.testgrid.api.domain.ErrorClass;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt as the public API shows it. {@code leaseExpired} is computed with the database clock:
 * an active attempt whose lease ran out (a dead worker, PRD-03 AC-4) is visibly not running.
 */
public record AttemptSummary(
    UUID id,
    int attemptNo,
    String workerId,
    AttemptStatus status,
    Instant claimedAt,
    @ApiNullable Instant startedAt,
    @ApiNullable Instant finishedAt,
    Instant leasedUntil,
    boolean leaseExpired,
    @ApiNullable Instant lastHeartbeatAt,
    @ApiNullable ErrorClass errorClass) {}
