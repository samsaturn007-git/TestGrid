package dev.testgrid.api.worker;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One registry row as {@code GET /api/v1/workers} shows it. {@code activeAttempts} counts this
 * worker's CLAIMED/RUNNING attempts (by name); a LOST row shows 0.
 */
public record WorkerView(
    UUID id,
    String name,
    String version,
    Executor executor,
    List<Engine> engines,
    int slots,
    WorkerState state,
    Instant startedAt,
    Instant lastSeenAt,
    int activeAttempts) {}
