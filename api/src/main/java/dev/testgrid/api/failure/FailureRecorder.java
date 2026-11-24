package dev.testgrid.api.failure;

import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.failure.FingerprintV1.Fingerprint;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-07 FR-1/FR-2 inside the completion transaction: fingerprint each failure, count it in its
 * group, then relabel every group it touched. Runs in the caller's transaction (MANDATORY), so the
 * case results, their group counts and the labels commit together or not at all.
 */
@Service
public class FailureRecorder {

  /** One failed, timed-out or flaky case, with its message already redacted. */
  public record CaseFailure(
      String caseKey, ErrorClass errorClass, List<String> frames, String redactedMessage) {}

  private final FailureRepository failures;
  private final FailureClassifier classifier;

  public FailureRecorder(FailureRepository failures, FailureClassifier classifier) {
    this.failures = failures;
    this.classifier = classifier;
  }

  /** Case key → the fingerprint row its result links to. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, UUID> recordCases(UUID projectId, List<CaseFailure> cases) {
    // Grouped and visited in fingerprint order: the row locks are always taken in one global
    // order, so concurrent completions in different runs can't deadlock on them.
    Map<String, Fingerprint> byValue = new TreeMap<>();
    Map<String, ErrorClass> classOf = new HashMap<>();
    Map<String, Integer> countOf = new HashMap<>();
    Map<String, String> valueOfCase = new HashMap<>();
    for (CaseFailure c : cases) {
      Fingerprint fp = FingerprintV1.of(c.errorClass(), c.frames(), c.redactedMessage());
      byValue.putIfAbsent(fp.value(), fp);
      classOf.putIfAbsent(fp.value(), c.errorClass());
      countOf.merge(fp.value(), 1, Integer::sum);
      valueOfCase.put(c.caseKey(), fp.value());
    }
    Map<String, UUID> idOfValue = new HashMap<>();
    byValue.forEach((value, fp) ->
        idOfValue.put(
            value, failures.upsert(projectId, classOf.get(value), fp, countOf.get(value))));
    Map<String, UUID> result = new HashMap<>();
    valueOfCase.forEach((caseKey, value) -> result.put(caseKey, idOfValue.get(value)));
    return result;
  }

  /** CONFLICT_REGISTER N25: an attempt-level failure is grouped from its error, with no frames. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordAttempt(UUID projectId, ErrorClass errorClass, String redactedMessage) {
    Fingerprint fp = FingerprintV1.of(errorClass, List.of(), redactedMessage);
    return failures.upsert(projectId, errorClass, fp, 1);
  }

  /** After the attempt row is final, so its status is part of the facts. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void classify(Collection<UUID> fingerprintIds) {
    fingerprintIds.stream()
        .distinct()
        .sorted()
        .forEach(id -> failures.classify(id, classifier.classify(failures.facts(id))));
  }
}
