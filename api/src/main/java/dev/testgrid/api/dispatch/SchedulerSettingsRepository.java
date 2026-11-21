package dev.testgrid.api.dispatch;

import dev.testgrid.api.domain.SchedulerPolicy;
import dev.testgrid.api.internal.InternalApiProperties;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the one {@code scheduler_settings} row, creating it from {@link SchedulerProperties} the
 * first time. Seeding on read rather than in a startup runner means a request that arrives before
 * startup finishes still sees a row, and two API instances starting together insert it only once.
 */
@Repository
public class SchedulerSettingsRepository {

  private final JdbcClient jdbc;
  private final SchedulerProperties seed;
  private final int heartbeatIntervalSeconds;

  public SchedulerSettingsRepository(
      JdbcClient jdbc, SchedulerProperties seed, InternalApiProperties internal) {
    this.jdbc = jdbc;
    this.seed = seed;
    this.heartbeatIntervalSeconds = internal.heartbeatIntervalSeconds();
  }

  public SchedulerSettings current() {
    SchedulerSettings settings = find().orElseGet(this::seedAndRead);
    // The heartbeat interval is a worker-side property, so the row's CHECK can't see it.
    if (settings.leaseTtlSeconds() <= heartbeatIntervalSeconds) {
      throw new IllegalStateException(
          "scheduler_settings.lease_ttl_s ("
              + settings.leaseTtlSeconds()
              + ") must be longer than the heartbeat interval ("
              + heartbeatIntervalSeconds
              + " s)");
    }
    return settings;
  }

  private SchedulerSettings seedAndRead() {
    jdbc.sql(
            """
            INSERT INTO scheduler_settings (global_max_sessions, max_attempts, lease_ttl_s)
            VALUES (:cap, :attempts, :ttl)
            ON CONFLICT (id) DO NOTHING
            """)
        .param("cap", seed.globalMaxSessions())
        .param("attempts", seed.maxAttempts())
        .param("ttl", seed.leaseTtlSeconds())
        .update();
    return find().orElseThrow();
  }

  private Optional<SchedulerSettings> find() {
    return jdbc.sql(
            """
            SELECT global_max_sessions, policy, aging_weight, max_attempts, lease_ttl_s
            FROM scheduler_settings
            """)
        .query((rs, i) ->
            new SchedulerSettings(
                rs.getInt("global_max_sessions"),
                SchedulerPolicy.valueOf(rs.getString("policy")),
                rs.getDouble("aging_weight"),
                rs.getInt("max_attempts"),
                rs.getInt("lease_ttl_s")))
        .optional();
  }
}
