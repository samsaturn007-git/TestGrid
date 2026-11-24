package dev.testgrid.api.quarantine;

import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Redactor;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import dev.testgrid.api.quality.QualityPolicy;
import dev.testgrid.api.quality.QualityPolicy.AutoMode;
import dev.testgrid.api.quality.QualityPolicyRepository;
import dev.testgrid.api.quarantine.QuarantineRepository.Expired;
import dev.testgrid.api.quarantine.QuarantineRepository.FlakyCase;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarantines (PRD-08 FR-3). The safeguards against hiding real bugs: an expiry of at most 30 days
 * (database clock, CHECK in V15), the policy's {@code maxActive} cap, an audit event for every
 * creation, revocation and expiry, and the evaluator still reporting each excused failure as {@code
 * QUARANTINED_FAIL}.
 */
@Service
public class QuarantineService {

  static final String OBJECT_TYPE = "quarantine";
  static final String CREATED = "QUARANTINE_CREATED";
  static final String REVOKED = "QUARANTINE_REVOKED";
  static final String EXPIRED = "QUARANTINE_EXPIRED";

  /** Contracts §12: AUTO needs a FLAKY label over at least 10 results and lasts 7 days. */
  static final int AUTO_MIN_OBSERVATIONS = 10;

  static final int AUTO_DAYS = 7;
  private static final int MAX_REASON = 500;
  private static final int LIST_LIMIT = 200;
  private static final int SUGGESTION_LIMIT = 50;
  private static final int EXPIRY_BATCH = 100;
  private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

  private final QuarantineRepository quarantines;
  private final QualityPolicyRepository policies;
  private final AuditRepository audit;
  private final CallerScope scope;

  public QuarantineService(
      QuarantineRepository quarantines,
      QualityPolicyRepository policies,
      AuditRepository audit,
      CallerScope scope) {
    this.quarantines = quarantines;
    this.policies = policies;
    this.audit = audit;
    this.scope = scope;
  }

  @Transactional
  public QuarantineView create(UUID projectId, CreateQuarantineRequest request) {
    requireVisible(projectId);
    String reason = Redactor.redact(request.reason().strip());
    if (reason.isEmpty() || reason.length() > MAX_REASON) {
      throw invalid("reason", "must be 1 … " + MAX_REASON + " characters");
    }
    QuarantineSource source = request.source() == null ? QuarantineSource.MANUAL : request.source();
    if (source == QuarantineSource.AUTO) {
      throw invalid("source", "must be MANUAL or SUGGESTED; only the API creates AUTO quarantines");
    }
    if (!quarantines.caseExists(projectId, request.caseKey())) {
      throw invalid("caseKey", "is not a case of any current suite");
    }
    if (!quarantines.expiryInRange(request.expiresAt())) {
      throw invalid("expiresAt", "must be in the future and at most 30 days ahead (server clock)");
    }
    quarantines.lockProject(projectId);
    quarantines
        .activeCovering(projectId, request.caseKey(), request.engine())
        .ifPresent(existing -> {
          throw new ApiProblemException(
              ProblemType.QUARANTINE_CONFLICT,
              request.caseKey()
                  + " is already quarantined"
                  + (request.engine() == null ? "" : " on " + request.engine().wireName())
                  + " by quarantine "
                  + existing);
        });
    int cap = maxActive(projectId);
    int active = quarantines.activeCount(projectId);
    if (active >= cap) {
      throw new ApiProblemException(
          ProblemType.QUARANTINE_CONFLICT,
          active
              + " quarantines are active and the policy allows at most "
              + cap
              + "; revoke one or wait for one to expire");
    }
    UUID id =
        quarantines.insert(
            projectId,
            request.caseKey(),
            request.engine(),
            reason,
            source,
            AuditRepository.LOCAL_ACTOR,
            request.expiresAt());
    audit(
        AuditRepository.LOCAL_ACTOR,
        CREATED,
        id,
        request.caseKey(),
        request.engine(),
        m -> {
          m.put("source", source.name());
          m.put("expiresAt", request.expiresAt().toString());
          m.put("reason", reason);
        });
    log.info(
        "quarantine created caseKey={} engine={} source={} expiresAt={}",
        request.caseKey(),
        request.engine(),
        source,
        request.expiresAt());
    return quarantines.find(projectId, id, false).orElseThrow();
  }

  /**
   * Revoking an active quarantine ends it now; revoking a revoked or expired one changes nothing.
   */
  @Transactional
  public QuarantineView revoke(UUID id) {
    UUID projectId = scope.projectId();
    QuarantineView before =
        quarantines
            .find(projectId, id, true)
            .orElseThrow(() ->
                new ApiProblemException(ProblemType.NOT_FOUND, "quarantine " + id + " not found"));
    if (before.state() == QuarantineState.ACTIVE && quarantines.revoke(id)) {
      audit(AuditRepository.LOCAL_ACTOR, REVOKED, id, before.caseKey(), before.engine(), m -> {});
      log.info("quarantine revoked caseKey={} engine={}", before.caseKey(), before.engine());
    }
    return quarantines.find(projectId, id, false).orElseThrow();
  }

  @Transactional(readOnly = true)
  public QuarantineList list(UUID projectId, QuarantineState state) {
    requireVisible(projectId);
    QualityPolicy.QuarantineSettings settings = settings(projectId);
    return new QuarantineList(
        quarantines.list(projectId, state, LIST_LIMIT),
        quarantines.activeCount(projectId),
        settings.maxActive(),
        settings.autoMode(),
        quarantines.now(),
        settings.autoMode() == AutoMode.SUGGEST
            ? quarantines.suggestions(projectId, SUGGESTION_LIMIT)
            : List.of());
  }

  /**
   * Writes one QUARANTINE_EXPIRED audit event per quarantine whose expiry has passed. The
   * quarantine stopped counting at {@code expires_at} whatever this pass does; it only records it.
   */
  @Transactional
  public int recordExpiries() {
    List<Expired> expired = quarantines.recordExpiries(EXPIRY_BATCH);
    for (Expired e : expired) {
      audit(
          AuditRepository.SYSTEM_ACTOR,
          EXPIRED,
          e.id(),
          e.caseKey(),
          e.engine() == null ? null : Engine.fromWireName(e.engine()),
          m -> m.put("expiresAt", e.expiresAt().toString()));
      log.info("quarantine expired caseKey={} engine={}", e.caseKey(), e.engine());
    }
    return expired.size();
  }

  /**
   * AUTO mode, called by the evaluator in its transaction after the run's evaluation is stored, so
   * a quarantine it creates applies to later runs only, never to the run that triggered it.
   * Quarantines each (case, engine) of the run that case health labels FLAKY over at least 10
   * results, for 7 days, while the cap allows.
   */
  public int autoQuarantine(UUID projectId, String suiteKey, UUID runId, QualityPolicy policy) {
    if (policy.quarantine().autoMode() != AutoMode.AUTO) {
      return 0;
    }
    List<FlakyCase> flaky =
        quarantines.flakyCasesOfRun(projectId, suiteKey, runId, AUTO_MIN_OBSERVATIONS);
    if (flaky.isEmpty()) {
      return 0;
    }
    quarantines.lockProject(projectId);
    int created = 0;
    int active = quarantines.activeCount(projectId);
    Instant expiresAt = quarantines.nowPlusDays(AUTO_DAYS);
    for (FlakyCase c : flaky) {
      if (quarantines.activeCovering(projectId, c.caseKey(), c.engine()).isPresent()) {
        continue;
      }
      if (active >= policy.quarantine().maxActive()) {
        log.warn(
            "AUTO quarantine skipped for {} on {}: {} active, cap {}",
            c.caseKey(),
            c.engine().wireName(),
            active,
            policy.quarantine().maxActive());
        break;
      }
      String reason = truncate("AUTO: " + c.reason() + " over " + c.observed() + " results");
      UUID id =
          quarantines.insert(
              projectId,
              c.caseKey(),
              c.engine(),
              reason,
              QuarantineSource.AUTO,
              AuditRepository.SYSTEM_ACTOR,
              expiresAt);
      audit(
          AuditRepository.SYSTEM_ACTOR,
          CREATED,
          id,
          c.caseKey(),
          c.engine(),
          m -> {
            m.put("source", QuarantineSource.AUTO.name());
            m.put("expiresAt", expiresAt.toString());
            m.put("reason", reason);
            m.put("runId", runId.toString());
          });
      active++;
      created++;
      log.info("AUTO quarantine created caseKey={} engine={}", c.caseKey(), c.engine());
    }
    return created;
  }

  private void audit(
      String actor,
      String action,
      UUID id,
      String caseKey,
      Engine engine,
      java.util.function.Consumer<Map<String, Object>> extra) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("caseKey", caseKey);
    metadata.put("engine", engine == null ? null : engine.wireName());
    extra.accept(metadata);
    audit.record(actor, action, OBJECT_TYPE, id, metadata);
  }

  private QualityPolicy.QuarantineSettings settings(UUID projectId) {
    return policies
        .active(projectId)
        .orElseThrow(() ->
            new IllegalStateException("project " + projectId + " has no active quality policy"))
        .policy()
        .quarantine();
  }

  private int maxActive(UUID projectId) {
    return settings(projectId).maxActive();
  }

  private static String truncate(String reason) {
    return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
  }

  private static ApiProblemException invalid(String field, String message) {
    return new ApiProblemException(
        ProblemType.VALIDATION_FAILED,
        "Request validation failed",
        List.of(new FieldError(field, message)));
  }

  private void requireVisible(UUID projectId) {
    if (!scope.canSee(projectId)) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }
  }
}
