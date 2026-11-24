package dev.testgrid.api.quality;

import dev.testgrid.api.compare.RunComparison;
import dev.testgrid.api.compare.RunComparisonService;
import dev.testgrid.api.quality.QualityEvaluationRepository.RunRow;
import dev.testgrid.api.quality.QualityEvaluationRepository.StoredEvaluation;
import dev.testgrid.api.quality.QualityPolicyRepository.StoredPolicy;
import dev.testgrid.api.quality.QualityRules.DurationFacts;
import dev.testgrid.api.quality.QualityRules.Regression;
import dev.testgrid.api.quality.QualityRules.RunFacts;
import dev.testgrid.api.quarantine.QuarantineRepository;
import dev.testgrid.api.quarantine.QuarantineService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates a finished run once (PRD-08 FR-2, CONFLICT_REGISTER N35): with the policy active at
 * that moment, after the finishing transaction has committed. Called by the quality loop and by
 * {@code GET /runs/{runId}/quality}; both end up with the same stored row.
 */
@Service
public class QualityEvaluator {

  private static final Logger log = LoggerFactory.getLogger(QualityEvaluator.class);

  private final QualityEvaluationRepository evaluations;
  private final QualityPolicyRepository policies;
  private final QualityFactsRepository facts;
  private final RunComparisonService comparisons;
  private final QuarantineRepository quarantines;
  private final QuarantineService quarantineService;

  public QualityEvaluator(
      QualityEvaluationRepository evaluations,
      QualityPolicyRepository policies,
      QualityFactsRepository facts,
      RunComparisonService comparisons,
      QuarantineRepository quarantines,
      QuarantineService quarantineService) {
    this.evaluations = evaluations;
    this.policies = policies;
    this.facts = facts;
    this.comparisons = comparisons;
    this.quarantines = quarantines;
    this.quarantineService = quarantineService;
  }

  /**
   * The run's evaluation, made now if it has none; empty while the run is unfinished or unknown.
   */
  @Transactional
  public Optional<StoredEvaluation> evaluate(UUID runId) {
    // Evaluations are append-only, so a stored one is final: answer it without taking the lock.
    Optional<StoredEvaluation> stored = evaluations.first(runId);
    if (stored.isPresent()) {
      return stored;
    }
    Optional<RunRow> locked = evaluations.lockRun(runId);
    if (locked.isEmpty() || !locked.get().status().isTerminal()) {
      return Optional.empty();
    }
    RunRow run = locked.get();
    Optional<StoredEvaluation> existing = evaluations.first(runId);
    if (existing.isPresent()) {
      return existing;
    }
    StoredPolicy policy =
        policies
            .active(run.projectId())
            .orElseThrow(() ->
                new IllegalStateException("project " + run.projectId() + " has no active policy"));
    RunFacts runFacts =
        new RunFacts(
            run.status(),
            facts.targets(runId),
            facts.cases(runId, run.suiteId()),
            duration(run, policy.policy()));
    QualityRules.Evaluation evaluation =
        QualityRules.evaluate(policy.policy(), runFacts, quarantines.activeFacts(run.projectId()));
    if (evaluations.insert(runId, policy.id(), policy.version(), evaluation)) {
      quarantineService.autoQuarantine(run.projectId(), run.suiteKey(), runId, policy.policy());
    }
    log.info(
        "quality evaluated run={} outcome={} policyVersion={}",
        runId,
        evaluation.outcome(),
        policy.version());
    return evaluations.first(runId);
  }

  private DurationFacts duration(RunRow run, QualityPolicy policy) {
    QualityPolicy.DurationRegression config = policy.durationRegression();
    if (!config.enabled()) {
      return DurationFacts.noLastGreen();
    }
    return comparisons
        .againstLastGreen(
            run.projectId(),
            run.id(),
            new RunComparison.Thresholds(config.maxRatio(), config.minDeltaMs()))
        .map(c ->
            new DurationFacts(
                c.lastGreenRunId(),
                c.rows().stream()
                    .filter(RunComparison.Row::durationRegression)
                    .map(r ->
                        new Regression(
                            r.caseKey(),
                            r.engine(),
                            r.headStatus(),
                            r.baseDurationMs(),
                            r.headDurationMs()))
                    .toList()))
        .orElseGet(DurationFacts::noLastGreen);
  }
}
