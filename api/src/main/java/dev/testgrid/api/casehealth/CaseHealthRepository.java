package dev.testgrid.api.casehealth;

import dev.testgrid.api.casehealth.CaseHealthWindow.Health;
import dev.testgrid.api.domain.Engine;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CaseHealthRepository {

  public record Key(UUID projectId, String suiteKey, String caseKey, Engine engine)
      implements Comparable<Key> {

    @Override
    public int compareTo(Key o) {
      int c = projectId.compareTo(o.projectId);
      if (c == 0) c = suiteKey.compareTo(o.suiteKey);
      if (c == 0) c = caseKey.compareTo(o.caseKey);
      if (c == 0) c = engine.compareTo(o.engine);
      return c;
    }
  }

  private final JdbcClient jdbc;

  public CaseHealthRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The stored window, locking the row (created empty on first sight) until commit. */
  public String lockWindow(Key key, int windowSize) {
    jdbc.sql(
            """
            INSERT INTO case_health (project_id, suite_key, case_key, engine, window_size,
                                     recent_outcomes, observed, pass_count, fail_count,
                                     flaky_count, flip_count, flip_rate, label, reason)
            VALUES (:p, :s, :c, :e, :w, '', 0, 0, 0, 0, 0, 0, 'INSUFFICIENT_DATA', 'no results yet')
            ON CONFLICT DO NOTHING
            """)
        .param("p", key.projectId())
        .param("s", key.suiteKey())
        .param("c", key.caseKey())
        .param("e", key.engine().wireName())
        .param("w", windowSize)
        .update();
    return jdbc.sql(
            """
            SELECT recent_outcomes FROM case_health
            WHERE project_id = :p AND suite_key = :s AND case_key = :c AND engine = :e
            FOR UPDATE
            """)
        .param("p", key.projectId())
        .param("s", key.suiteKey())
        .param("c", key.caseKey())
        .param("e", key.engine().wireName())
        .query(String.class)
        .single();
  }

  public void store(Key key, Health h) {
    jdbc.sql(
            """
            UPDATE case_health
            SET window_size = :w, recent_outcomes = :o, observed = :n, pass_count = :pc,
                fail_count = :fc, flaky_count = :kc, flip_count = :flips, flip_rate = :rate,
                label = :label, reason = :reason, computed_at = now(), version = version + 1
            WHERE project_id = :p AND suite_key = :s AND case_key = :c AND engine = :e
            """)
        .param("p", key.projectId())
        .param("s", key.suiteKey())
        .param("c", key.caseKey())
        .param("e", key.engine().wireName())
        .param("w", h.windowSize())
        .param("o", h.outcomes())
        .param("n", h.observed())
        .param("pc", h.passCount())
        .param("fc", h.failCount())
        .param("kc", h.flakyCount())
        .param("flips", h.flipCount())
        .param("rate", h.flipRate())
        .param("label", h.label().name())
        .param("reason", h.reason())
        .update();
  }
}
