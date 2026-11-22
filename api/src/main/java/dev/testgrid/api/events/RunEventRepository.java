package dev.testgrid.api.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads {@code run_events} for SSE replay. Served by the {@code run_events(run_id, id)} index. */
@Repository
public class RunEventRepository {

  /** Contracts §5 envelope, exactly as the stream sends it. */
  public record Envelope(
      long eventId,
      int schemaVersion,
      String type,
      UUID runId,
      UUID targetId,
      UUID attemptId,
      Instant occurredAt,
      JsonNode data) {}

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  public RunEventRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** Up to {@code limit} events of the run with {@code id > afterId}, oldest first. */
  public List<Envelope> after(UUID runId, long afterId, int limit) {
    return jdbc.sql(
            """
            SELECT id, schema_version, type, run_id, target_id, attempt_id, occurred_at,
                   payload::text AS payload
            FROM run_events
            WHERE run_id = :r AND id > :after
            ORDER BY id
            LIMIT :limit
            """)
        .param("r", runId)
        .param("after", afterId)
        .param("limit", limit)
        .query((rs, i) ->
            new Envelope(
                rs.getLong("id"),
                rs.getInt("schema_version"),
                rs.getString("type"),
                rs.getObject("run_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("occurred_at", Timestamp.class).toInstant(),
                mapper.readTree(rs.getString("payload"))))
        .list();
  }

  /** The run's newest event id, or 0 when it has none. */
  public long latestId(UUID runId) {
    return jdbc.sql("SELECT coalesce(max(id), 0) FROM run_events WHERE run_id = :r")
        .param("r", runId)
        .query(Long.class)
        .single();
  }
}
