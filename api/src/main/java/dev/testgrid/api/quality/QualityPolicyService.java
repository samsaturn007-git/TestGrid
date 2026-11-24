package dev.testgrid.api.quality;

import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Redactor;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import dev.testgrid.api.quality.QualityPolicyRepository.StoredPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and versions the quality policy (PRD-08 FR-1). Every change is audited. */
@Service
public class QualityPolicyService {

  static final String CHANGE_ACTION = "QUALITY_POLICY_CHANGED";
  static final String OBJECT_TYPE = "quality_policy";
  private static final int MAX_REASON = 500;
  private static final int HISTORY_LIMIT = 100;
  private static final Logger log = LoggerFactory.getLogger(QualityPolicyService.class);

  private final QualityPolicyRepository policies;
  private final AuditRepository audit;
  private final CallerScope scope;

  public QualityPolicyService(
      QualityPolicyRepository policies, AuditRepository audit, CallerScope scope) {
    this.policies = policies;
    this.audit = audit;
    this.scope = scope;
  }

  /** The active version, or {@code version} when given. */
  @Transactional(readOnly = true)
  public QualityPolicyView get(UUID projectId, Integer version) {
    requireVisible(projectId);
    StoredPolicy policy =
        (version == null ? policies.active(projectId) : policies.version(projectId, version))
            .orElseThrow(() ->
                new ApiProblemException(
                    ProblemType.NOT_FOUND,
                    version == null
                        ? "project " + projectId + " has no quality policy"
                        : "quality policy version " + version + " not found"));
    return view(policy);
  }

  @Transactional
  public QualityPolicyView update(UUID projectId, PolicyUpdateRequest request) {
    requireVisible(projectId);
    String reason = Redactor.redact(request.reason().strip());
    if (reason.isEmpty() || reason.length() > MAX_REASON) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "Request validation failed",
          List.of(new FieldError("reason", "must be 1 … " + MAX_REASON + " characters")));
    }
    if (request.policy().requiredTargets().stream().distinct().count()
        != request.policy().requiredTargets().size()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "Request validation failed",
          List.of(new FieldError("policy.requiredTargets", "must not repeat a target")));
    }
    var replaced = policies.replaceActive(projectId, request.policy());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(
        "fromVersion", replaced.previousVersion() == 0 ? null : replaced.previousVersion());
    metadata.put("toVersion", replaced.current().version());
    metadata.put("reason", reason);
    audit.record(
        AuditRepository.LOCAL_ACTOR, CHANGE_ACTION, OBJECT_TYPE, replaced.current().id(), metadata);
    log.info(
        "quality policy changed version {} -> {}",
        replaced.previousVersion(),
        replaced.current().version());
    return view(replaced.current());
  }

  private QualityPolicyView view(StoredPolicy policy) {
    return new QualityPolicyView(
        policy.id(),
        policy.projectId(),
        policy.version(),
        policy.active(),
        policy.createdAt(),
        policy.policy(),
        policies.versions(policy.projectId(), HISTORY_LIMIT));
  }

  private void requireVisible(UUID projectId) {
    if (!scope.canSee(projectId)) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }
  }
}
