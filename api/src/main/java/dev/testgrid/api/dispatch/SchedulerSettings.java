package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.SchedulerPolicy;

/** The single {@code scheduler_settings} row (contracts §3). */
public record SchedulerSettings(
    int globalMaxSessions,
    SchedulerPolicy policy,
    double agingWeight,
    int maxAttempts,
    int leaseTtlSeconds) {}
