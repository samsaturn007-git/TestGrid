package dev.testgrid.api.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * First-start values for the {@code scheduler_settings} row (CONFLICT_REGISTER N15). Once the row
 * exists the database is the only source; changing these properties later has no effect.
 */
@ConfigurationProperties("testgrid.scheduler")
public record SchedulerProperties(
    @DefaultValue("3") int globalMaxSessions,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("30") int leaseTtlSeconds) {}
