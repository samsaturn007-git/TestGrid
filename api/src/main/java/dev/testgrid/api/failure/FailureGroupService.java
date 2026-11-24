package dev.testgrid.api.failure;

import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactRepository;
import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Page;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
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
  private final ArtifactRepository artifacts;

  public FailureGroupService(
      JdbcClient jdbc,
      JsonMapper mapper,
      CallerScope scope,
      AuditRepository audit,
      ArtifactRepository artifacts) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.scope = scope;
    this.audit = audit;
    this.artifacts = artifacts;
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
    return jdbc
        .sql(
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
                instant(rs, "finished_at"),
                List.of()))
        .list()
        .stream()
        .collect(Collectors.collectingAndThen(Collectors.toList(), this::withArtifacts));
  }

  /** One artifact query for the whole page of occurrences, not one per occurrence. */
  private List<Occurrence> withArtifacts(List<Occurrence> occurrences) {
    Map<UUID, List<ArtifactView>> byAttempt =
        artifacts.byAttempts(occurrences.stream().map(Occurrence::attemptId).distinct().toList());
    return occurrences.stream()
        .map(o -> {
          List<ArtifactView> mine =
              byAttempt.getOrDefault(o.attemptId(), List.of()).stream()
                  .filter(a ->
                      a.kind() == ArtifactKind.LOG
                          || (o.caseKey() != null && o.caseKey().equals(a.caseKey())))
                  .toList();
          return new Occurrence(
              o.runId(),
              o.targetId(),
              o.attemptId(),
              o.attemptNo(),
              o.engine(),
              o.suiteKey(),
              o.caseKey(),
              o.status(),
              o.errorMessage(),
              o.occurredAt(),
              mine);
        })
        .toList();
  }

  /**
   * GET /failure-groups (FR-7): most occurrences first. {@code categories} match the shown category
   * (override, else rule); {@code engine} keeps groups with an occurrence on it; {@code
   * from}/{@code to} bound {@code last_seen_at} (from inclusive, to exclusive). Served by {@code
   * failure_fingerprints_list}; the page's engines, cases and run counts come from one second query
   * over its ids.
   */
  @Transactional(readOnly = true)
  public Page<FailureGroupSummary> list(
      Set<FailureCategory> categories,
      Engine engine,
      Instant from,
      Instant to,
      int limit,
      FailureGroupCursor cursor) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, algo_version, fingerprint, error_class, category, confidence, rule_id,
                   override_category, sample_message, occurrence_count, first_seen_at, last_seen_at
            FROM failure_fingerprints f
            WHERE project_id = :p
            """);
    Map<String, Object> params = new HashMap<>();
    params.put("p", scope.projectId());
    if (!categories.isEmpty()) {
      sql.append(" AND coalesce(override_category, category) IN (:cats)");
      params.put("cats", categories.stream().map(Enum::name).sorted().toList());
    }
    if (engine != null) {
      sql.append(
          """
           AND (EXISTS (SELECT 1 FROM case_results cr JOIN attempts a ON a.id = cr.attempt_id
                        JOIN run_targets t ON t.id = a.target_id
                        WHERE cr.fingerprint_id = f.id AND t.engine = :engine)
                OR EXISTS (SELECT 1 FROM attempts a JOIN run_targets t ON t.id = a.target_id
                           WHERE a.fingerprint_id = f.id AND t.engine = :engine))
          """);
      params.put("engine", engine.wireName());
    }
    if (from != null) {
      sql.append(" AND last_seen_at >= :from");
      params.put("from", Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" AND last_seen_at < :to");
      params.put("to", Timestamp.from(to));
    }
    if (cursor != null) {
      sql.append(" AND (occurrence_count, id) < (:cc, :cid)");
      params.put("cc", cursor.occurrenceCount());
      params.put("cid", cursor.id());
    }
    sql.append(" ORDER BY occurrence_count DESC, id DESC LIMIT :limit");
    params.put("limit", limit + 1);
    List<SummaryRow> rows = jdbc.sql(sql.toString()).params(params).query(SUMMARY).list();
    boolean more = rows.size() > limit;
    List<SummaryRow> page = more ? rows.subList(0, limit) : rows;
    Map<UUID, Spread> spread = spread(page.stream().map(SummaryRow::id).toList());
    List<FailureGroupSummary> items =
        page.stream()
            .map(r -> {
              Spread s = spread.getOrDefault(r.id(), new Spread(List.of(), List.of(), false, 0));
              return new FailureGroupSummary(
                  r.id(),
                  r.algoVersion(),
                  r.fingerprint(),
                  r.errorClass(),
                  r.overrideCategory() == null ? r.ruleCategory() : r.overrideCategory(),
                  r.ruleCategory(),
                  r.ruleId(),
                  r.confidence(),
                  r.overrideCategory() != null,
                  r.sampleMessage(),
                  r.occurrenceCount(),
                  s.runs(),
                  s.engines(),
                  s.cases(),
                  s.wholeTarget(),
                  r.firstSeenAt(),
                  r.lastSeenAt(),
                  new FailureGroupSummary.Links("/api/v1/failure-groups/" + r.id()));
            })
            .toList();
    SummaryRow last = page.isEmpty() ? null : page.getLast();
    String next =
        more && last != null
            ? new FailureGroupCursor(last.occurrenceCount(), last.id()).encode()
            : null;
    return new Page<>(items, next);
  }

  private record SummaryRow(
      UUID id,
      String algoVersion,
      String fingerprint,
      ErrorClass errorClass,
      FailureCategory ruleCategory,
      BigDecimal confidence,
      String ruleId,
      FailureCategory overrideCategory,
      String sampleMessage,
      int occurrenceCount,
      Instant firstSeenAt,
      Instant lastSeenAt) {}

  private static final RowMapper<SummaryRow> SUMMARY =
      (rs, i) -> {
        String override = rs.getString("override_category");
        return new SummaryRow(
            rs.getObject("id", UUID.class),
            rs.getString("algo_version"),
            rs.getString("fingerprint"),
            ErrorClass.valueOf(rs.getString("error_class")),
            FailureCategory.valueOf(rs.getString("category")),
            rs.getBigDecimal("confidence"),
            rs.getString("rule_id"),
            override == null ? null : FailureCategory.valueOf(override),
            rs.getString("sample_message"),
            rs.getInt("occurrence_count"),
            instant(rs, "first_seen_at"),
            instant(rs, "last_seen_at"));
      };

  private record Spread(List<Engine> engines, List<String> cases, boolean wholeTarget, int runs) {}

  private Map<UUID, Spread> spread(List<UUID> ids) {
    Map<UUID, Spread> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            WITH occ AS (
              SELECT cr.fingerprint_id AS fp, cr.case_key, t.engine, t.run_id
              FROM case_results cr
              JOIN attempts a ON a.id = cr.attempt_id
              JOIN run_targets t ON t.id = a.target_id
              WHERE cr.fingerprint_id IN (:ids)
              UNION ALL
              SELECT a.fingerprint_id, NULL, t.engine, t.run_id
              FROM attempts a JOIN run_targets t ON t.id = a.target_id
              WHERE a.fingerprint_id IN (:ids)
            )
            SELECT fp, array_agg(DISTINCT engine) AS engines,
                   array_remove(array_agg(DISTINCT case_key), NULL) AS cases,
                   bool_or(case_key IS NULL) AS whole_target,
                   count(DISTINCT run_id) AS runs
            FROM occ GROUP BY fp
            """)
        .param("ids", ids)
        .query(rs -> {
          List<Engine> engines =
              Arrays.stream((Object[]) rs.getArray("engines").getArray())
                  .map(v -> Engine.fromWireName(String.valueOf(v)))
                  .sorted()
                  .toList();
          List<String> cases =
              Arrays.stream((Object[]) rs.getArray("cases").getArray())
                  .map(String::valueOf)
                  .sorted()
                  .toList();
          out.put(
              rs.getObject("fp", UUID.class),
              new Spread(engines, cases, rs.getBoolean("whole_target"), rs.getInt("runs")));
        });
    return out;
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
