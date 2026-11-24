package dev.testgrid.api.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code audit_events} (contracts §3, CONFLICT_REGISTER N26): append-only, a trigger rejects UPDATE
 * and DELETE. Written in the transaction of the change it records.
 */
@Repository
public class AuditRepository {

  /** Until PRD-12 there is no authentication, so every human action is the local user. */
  public static final String LOCAL_ACTOR = "local";

  /** The API itself: an AUTO quarantine, or the expiry of one (PRD-08 FR-3). */
  public static final String SYSTEM_ACTOR = "system";

  public record AuditEntry(
      UUID id, String actor, String action, Instant occurredAt, Map<String, Object> metadata) {}

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  public AuditRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** {@code metadata} must already be redacted: it is stored and served as is. */
  public void record(
      String actor, String action, String objectType, UUID objectId, Map<String, ?> metadata) {
    jdbc.sql(
            """
            INSERT INTO audit_events (actor, action, object_type, object_id, metadata_redacted)
            VALUES (:actor, :action, :type, :id, CAST(:m AS jsonb))
            """)
        .param("actor", actor)
        .param("action", action)
        .param("type", objectType)
        .param("id", objectId)
        .param("m", mapper.writeValueAsString(metadata))
        .update();
  }

  /** Oldest first, at most {@code limit}. */
  public List<AuditEntry> forObject(String objectType, UUID objectId, int limit) {
    return jdbc.sql(
            """
            SELECT id, actor, action, occurred_at, metadata_redacted::text AS m
            FROM audit_events
            WHERE object_type = :type AND object_id = :id
            ORDER BY occurred_at, id
            LIMIT :limit
            """)
        .param("type", objectType)
        .param("id", objectId)
        .param("limit", limit)
        .query((rs, i) ->
            new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getTimestamp("occurred_at").toInstant(),
                mapper.readValue(rs.getString("m"), MAP)))
        .list();
  }
}
