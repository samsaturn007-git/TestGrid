package dev.testgrid.api.failure;

import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Redactor;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import dev.testgrid.api.failure.FailureClassifier.Evidence;
import dev.testgrid.api.failure.FailureGroupView.Occurrence;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Reads a failure group and applies the audited human override (PRD-07 FR-3). */
@Service
public class FailureGroupService {

  static final String OBJECT_TYPE = "failure_fingerprint";
  static final String OVERRIDE_ACTION = "FAILURE_CATEGORY_OVERRIDDEN";
  static final int OCCURRENCE_LIMIT = 50;
  static final int AUDIT_LIMIT = 100;
  static final int MAX_REASON = 500;

  private static final Logger log = LoggerFactory.getLogger(FailureGroupService.class);

  private final JdbcClient jdbc;
  private final JsonMapper mapper;
  private final CallerScope scope;
  private final AuditRepository audit;

  public FailureGroupService(
      JdbcClient jdbc, JsonMapper mapper, CallerScope scope, AuditRepository audit) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.scope = scope;
    this.audit = audit;
  }

  private record Row(
      UUID id,
      String algoVersion,
      String fingerprint,
      ErrorClass errorClass,
      FailureCategory ruleCategory,
      BigDecimal confidence,
      String ruleId,
      String evidenceJson,
      String sampleMessage,
      int occurrenceCount,
      Instant firstSeenAt,
      Instant lastSeenAt,
      FailureCategory overrideCategory,
      String overrideReason,
      String overriddenBy,
      Instant overriddenAt) {

    FailureCategory effective() {
      return overrideCategory == null ? ruleCategory : overrideCategory;
    }
  }

  @Transactional(readOnly = true)
  public FailureGroupView get(UUID id) {
    return view(row(id, false));
  }

  /**
   * Stores who, why and when, and an audit row, in one transaction. The rule keeps relabelling the
   * group as new occurrences arrive, but the override stays in front of it for this fingerprint
   * version. Sending the override already in place changes nothing and is not audited again.
   */
  @Transactional
  public FailureGroupView override(UUID id, OverrideRequest request) {
    String reason = Redactor.redact(request.reason().strip());
    if (reason.isEmpty() || reason.length() > MAX_REASON) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "Request validation failed",
          List.of(new FieldError("reason", "must be 1 … " + MAX_REASON + " characters")));
    }
    Row before = row(id, true);
    if (request.category() == before.overrideCategory() && reason.equals(before.overrideReason())) {
      return view(before);
    }
    jdbc.sql(
            """
            UPDATE failure_fingerprints
            SET override_category = :c, override_reason = :r, overridden_by = :by,
                overridden_at = now(), version = version + 1
            WHERE id = :id
            """)
        .param("id", id)
        .param("c", request.category().name())
        .param("r", reason)
        .param("by", AuditRepository.LOCAL_ACTOR)
        .update();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("from", before.effective().name());
    metadata.put("to", request.category().name());
    metadata.put("ruleId", before.ruleId());
    metadata.put("ruleCategory", before.ruleCategory().name());
    metadata.put("confidence", before.confidence());
    metadata.put("algoVersion", before.algoVersion());
    metadata.put("fingerprint", before.fingerprint());
    metadata.put("reason", reason);
    audit.record(AuditRepository.LOCAL_ACTOR, OVERRIDE_ACTION, OBJECT_TYPE, id, metadata);
    log.info(
        "failure group {} overridden {} -> {} (rule {} {})",
        before.fingerprint(),
        before.effective(),
        request.category(),
        before.ruleId(),
        before.ruleCategory());
    return view(row(id, false));
  }

  private Row row(UUID id, boolean forUpdate) {
    return jdbc.sql(
            """
            SELECT id, algo_version, fingerprint, error_class, category, confidence, rule_id,
                   evidence::text AS evidence, sample_message, occurrence_count, first_seen_at,
                   last_seen_at, override_category, override_reason, overridden_by, overridden_at
            FROM failure_fingerprints
            WHERE id = :id AND project_id = :p
            """
                + (forUpdate ? " FOR UPDATE" : ""))
        .param("id", id)
        .param("p", scope.projectId())
        .query(FailureGroupService::mapRow)
        .optional()
        .orElseThrow(() ->
            new ApiProblemException(ProblemType.NOT_FOUND, "failure group " + id + " not found"));
  }

  private FailureGroupView view(Row row) {
    FailureGroupView.Override override =
        row.overrideCategory() == null
            ? null
            : new FailureGroupView.Override(
                row.overrideCategory(),
                row.overrideReason(),
                row.overriddenBy(),
                row.overriddenAt());
    return new FailureGroupView(
        row.id(),
        row.algoVersion(),
        row.fingerprint(),
        row.errorClass(),
        row.effective(),
        row.ruleCategory(),
        row.ruleId(),
        row.confidence(),
        row.sampleMessage(),
        row.occurrenceCount(),
        row.firstSeenAt(),
        row.lastSeenAt(),
        override,
        mapper.readValue(row.evidenceJson(), Evidence.class),
        occurrences(row.id()),
        audit.forObject(OBJECT_TYPE, row.id(), AUDIT_LIMIT));
  }

  /** Newest first, at most {@value #OCCURRENCE_LIMIT}; {@code occurrenceCount} is the total. */
  private List<Occurrence> occurrences(UUID id) {
    return jdbc.sql(
            """
            SELECT * FROM (
              SELECT t.run_id, t.id AS target_id, a.id AS attempt_id, a.attempt_no, t.engine,
                     s.suite_key, cr.case_key, cr.status, cr.error_message_redacted AS message,
                     a.finished_at
              FROM case_results cr
              JOIN attempts a ON a.id = cr.attempt_id
              JOIN run_targets t ON t.id = a.target_id
              JOIN runs r ON r.id = t.run_id
              JOIN suites s ON s.id = r.suite_id
              WHERE cr.fingerprint_id = :id
              UNION ALL
              SELECT t.run_id, t.id, a.id, a.attempt_no, t.engine, s.suite_key, NULL, a.status,
                     a.error_detail_redacted, a.finished_at
              FROM attempts a
              JOIN run_targets t ON t.id = a.target_id
              JOIN runs r ON r.id = t.run_id
              JOIN suites s ON s.id = r.suite_id
              WHERE a.fingerprint_id = :id
            ) o
            ORDER BY finished_at DESC, attempt_id DESC, case_key
            LIMIT :limit
            """)
        .param("id", id)
        .param("limit", OCCURRENCE_LIMIT)
        .query((rs, i) ->
            new Occurrence(
                rs.getObject("run_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getInt("attempt_no"),
                Engine.fromWireName(rs.getString("engine")),
                rs.getString("suite_key"),
                rs.getString("case_key"),
                rs.getString("status"),
                rs.getString("message"),
                instant(rs, "finished_at")))
        .list();
  }

  private static Row mapRow(ResultSet rs, int i) throws SQLException {
    String override = rs.getString("override_category");
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("algo_version"),
        rs.getString("fingerprint"),
        ErrorClass.valueOf(rs.getString("error_class")),
        FailureCategory.valueOf(rs.getString("category")),
        rs.getBigDecimal("confidence"),
        rs.getString("rule_id"),
        rs.getString("evidence"),
        rs.getString("sample_message"),
        rs.getInt("occurrence_count"),
        instant(rs, "first_seen_at"),
        instant(rs, "last_seen_at"),
        override == null ? null : FailureCategory.valueOf(override),
        rs.getString("override_reason"),
        rs.getString("overridden_by"),
        instant(rs, "overridden_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return Objects.isNull(t) ? null : t.toInstant();
  }
}
