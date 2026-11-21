package dev.testgrid.api.domain;

import static dev.testgrid.api.domain.CaseStatus.FAILED;
import static dev.testgrid.api.domain.CaseStatus.FLAKY;
import static dev.testgrid.api.domain.CaseStatus.PASSED;
import static dev.testgrid.api.domain.CaseStatus.SKIPPED;
import static dev.testgrid.api.domain.CaseStatus.TIMED_OUT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contracts §2.2 target status from case results, against an independent oracle. */
class TargetOutcomeTest {

  @Test
  void readableExamples() {
    assertThat(TargetOutcome.of(List.of(PASSED, PASSED)).status()).isEqualTo(TargetStatus.PASSED);
    assertThat(TargetOutcome.of(List.of(PASSED, FLAKY, SKIPPED)).status())
        .isEqualTo(TargetStatus.PASSED);
    assertThat(TargetOutcome.of(List.of(TIMED_OUT, FAILED)).status())
        .isEqualTo(TargetStatus.FAILED);
    assertThat(TargetOutcome.of(List.of(PASSED, TIMED_OUT)).status())
        .isEqualTo(TargetStatus.TIMED_OUT);
    assertThat(TargetOutcome.of(List.of(SKIPPED, SKIPPED)))
        .isEqualTo(new TargetOutcome.Outcome(TargetStatus.INFRA_ERROR, ErrorClass.CONFIG));
  }

  @Test
  void everyCombinationOfUpToFourCasesMatchesTheOracle() {
    CaseStatus[] all = CaseStatus.values();
    int checked = 0;
    for (int n = 1; n <= 4; n++) {
      int combos = (int) Math.pow(all.length, n);
      for (int code = 0; code < combos; code++) {
        List<CaseStatus> cases = new ArrayList<>();
        for (int i = 0, c = code; i < n; i++, c /= all.length) {
          cases.add(all[c % all.length]);
        }
        assertThat(TargetOutcome.of(cases).status()).as(cases.toString()).isEqualTo(oracle(cases));
        checked++;
      }
    }
    assertThat(checked).isEqualTo(5 + 25 + 125 + 625);
  }

  /** Written from the contract's wording, not from the implementation. */
  private static TargetStatus oracle(List<CaseStatus> cases) {
    long executed = cases.stream().filter(c -> c != SKIPPED).count();
    if (executed == 0) return TargetStatus.INFRA_ERROR;
    for (CaseStatus c : cases) if (c == FAILED) return TargetStatus.FAILED;
    for (CaseStatus c : cases) if (c == TIMED_OUT) return TargetStatus.TIMED_OUT;
    return TargetStatus.PASSED;
  }
}
