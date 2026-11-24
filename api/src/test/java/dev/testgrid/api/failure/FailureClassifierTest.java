package dev.testgrid.api.failure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.domain.FailureCategory;
import dev.testgrid.api.failure.FailureClassifier.Classification;
import dev.testgrid.api.failure.FailureClassifier.Facts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FailureClassifierTest {

  private final FailureClassifier classifier = new FailureClassifier(FailureRulesTest.shipped());

  private static Facts facts(
      ErrorClass errorClass, int occurrences, int attempts, int passedOnRetry, String... statuses) {
    return new Facts(
        errorClass, occurrences, attempts, attempts, 1, passedOnRetry, Set.of(statuses));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  @Test
  void theSixFixtureScenariosGetTheirExpectedCategory() {
    // SECURITY_AND_TEST_STRATEGY "Fixture and fault scenarios": the ground truth for PRD-07.
    assertThat(classifier.classify(facts(ErrorClass.ASSERTION, 3, 3, 0, "FAILED")).category())
        .as("PRODUCT_DEFECT")
        .isEqualTo(FailureCategory.PRODUCT_SUSPECTED);
    assertThat(
            classifier.classify(facts(ErrorClass.ASSERTION, 2, 2, 1, "FAILED", "FLAKY")).category())
        .as("AUTOMATION_FLAKE")
        .isEqualTo(FailureCategory.AUTOMATION_FLAKE);
    assertThat(
            classifier.classify(facts(ErrorClass.SELECTOR_NOT_FOUND, 2, 1, 0, "FAILED")).category())
        .as("SELECTOR_DRIFT")
        .isEqualTo(FailureCategory.TEST_BROKEN);
    assertThat(classifier.classify(facts(ErrorClass.TEST_TIMEOUT, 1, 1, 0, "TIMED_OUT")).category())
        .as("TIMEOUT")
        .isEqualTo(FailureCategory.TIMEOUT);
    assertThat(
            classifier
                .classify(facts(ErrorClass.BROWSER_LAUNCH, 1, 1, 0, "INFRA_ERROR"))
                .category())
        .as("BROWSER_LAUNCH")
        .isEqualTo(FailureCategory.ENVIRONMENT);
  }

  @Test
  void aSingleAssertionFailureIsNotYetAProductDefect() {
    Classification c = classifier.classify(facts(ErrorClass.ASSERTION, 1, 1, 0, "FAILED"));
    assertThat(c.category()).isEqualTo(FailureCategory.UNKNOWN);
    assertThat(c.ruleId()).isEqualTo("R6");
    assertThat(c.evidence().rulesTried())
        .filteredOn(check -> check.ruleId().equals("R5"))
        .singleElement()
        .satisfies(check -> assertThat(check.reason()).isEqualTo("seen in 1 attempt(s) (needs 2)"));
  }

  @Test
  void anyAssertionRecordedAsTimedOutIsATimeoutFirst() {
    assertThat(classifier.classify(facts(ErrorClass.ASSERTION, 2, 2, 0, "TIMED_OUT")).ruleId())
        .isEqualTo("R2");
  }

  @Test
  void theFirstMatchingRuleWinsAndEarlierMissesAreExplained() {
    Classification c = classifier.classify(facts(ErrorClass.ASSERTION, 3, 3, 0, "FAILED"));
    assertThat(c.ruleId()).isEqualTo("R5");
    assertThat(c.evidence().rulesTried())
        .extracting(FailureClassifier.RuleCheck::ruleId, FailureClassifier.RuleCheck::matched)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("R1", false),
            org.assertj.core.groups.Tuple.tuple("R2", false),
            org.assertj.core.groups.Tuple.tuple("R3", false),
            org.assertj.core.groups.Tuple.tuple("R4", false),
            org.assertj.core.groups.Tuple.tuple("R5", true));
    assertThat(c.evidence().rulesTried().get(1).reason())
        .isEqualTo("error class ASSERTION is not [TEST_TIMEOUT] or no occurrence is TIMED_OUT");
    assertThat(c.evidence().rulesTried().get(3).reason())
        .isEqualTo("0 of 3 occurrences passed on a retry (needs 1)");
    assertThat(c.evidence().explanation()).contains("may be wrong");
  }

  @Test
  void confidenceIsBasePlusOccurrencesMinusDisagreementWithinFloorAndCap() {
    // R5: 0.70 + 0.02 × (3 − 1) − 0.30 × (1 − 3/3) = 0.74
    var work =
        classifier.classify(facts(ErrorClass.ASSERTION, 3, 3, 0, "FAILED")).evidence().confidence();
    assertThat(work.base()).isEqualByComparingTo(d("0.70"));
    assertThat(work.occurrenceBonus()).isEqualByComparingTo(d("0.04"));
    assertThat(work.disagreementPenalty()).isEqualByComparingTo(d("0.00"));
    assertThat(work.value()).isEqualByComparingTo(d("0.74"));
    // R4 with 1 of 4 occurrences passing on retry: 0.60 + 0.06 − 0.23 (0.30 × 0.75, rounded) = 0.43
    var flake = classifier.classify(facts(ErrorClass.ASSERTION, 4, 4, 1, "FAILED", "FLAKY"));
    assertThat(flake.ruleId()).isEqualTo("R4");
    assertThat(flake.evidence().confidence().agreement()).isEqualByComparingTo(d("0.25"));
    assertThat(flake.confidence()).isEqualByComparingTo(d("0.43"));
    // The occurrence bonus stops after 5 extra occurrences, and nothing passes the cap.
    assertThat(
            classifier
                .classify(facts(ErrorClass.TEST_TIMEOUT, 500, 500, 0, "TIMED_OUT"))
                .confidence())
        .isEqualByComparingTo(d("0.95"));
    // A selector failure that mostly passes on retry loses confidence: 0.80 + 0.10 − 0.27 = 0.63.
    assertThat(
            classifier
                .classify(facts(ErrorClass.SELECTOR_NOT_FOUND, 10, 10, 9, "FLAKY", "FAILED"))
                .confidence())
        .isEqualByComparingTo(d("0.63"));
  }

  @Test
  void int002NoInfrastructureFaultIsEverLabelledProductSuspected() {
    // Every infrastructure error class against every combination of the facts the rules read.
    List<String> violations = new ArrayList<>();
    for (ErrorClass errorClass : FailureRules.INFRASTRUCTURE) {
      for (int occurrences = 1; occurrences <= 6; occurrences++) {
        for (int attempts = 1; attempts <= occurrences; attempts++) {
          for (int flaky = 0; flaky <= occurrences; flaky++) {
            for (Set<String> statuses :
                List.of(
                    Set.of("FAILED"),
                    Set.of("INFRA_ERROR"),
                    Set.of("FAILED", "FLAKY"),
                    Set.of("TIMED_OUT"))) {
              Facts f = new Facts(errorClass, occurrences, attempts, attempts, 3, flaky, statuses);
              if (classifier.classify(f).category() == FailureCategory.PRODUCT_SUSPECTED) {
                violations.add(f.toString());
              }
            }
          }
        }
      }
    }
    assertThat(violations).isEmpty();
  }
}
