package dev.testgrid.api.casehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.casehealth.CaseHealthWindow.Health;
import dev.testgrid.api.casehealth.CaseHealthWindow.Label;
import dev.testgrid.api.domain.CaseStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CaseHealthWindowTest {

  static final CaseHealthProperties DEFAULTS = new CaseHealthProperties(20, 5, 0.2, 2, 3);

  private static Health of(String outcomes) {
    return CaseHealthWindow.of(outcomes, DEFAULTS);
  }

  @Test
  void fewerThanFiveResultsAreInsufficientData() {
    assertThat(of("").label()).isEqualTo(Label.INSUFFICIENT_DATA);
    Health four = of("FPFP");
    assertThat(four.label()).isEqualTo(Label.INSUFFICIENT_DATA);
    assertThat(four.reason()).isEqualTo("4 results; a label needs at least 5");
    assertThat(four.flipCount()).isEqualTo(3);
  }

  @Test
  void flipRateIsTransitionsOverNMinusOne() {
    Health alternating = of("PFPFPF");
    assertThat(alternating.flipCount()).isEqualTo(5);
    assertThat(alternating.flipRate()).isEqualByComparingTo("1.000");
    assertThat(alternating.label()).isEqualTo(Label.FLAKY);
    assertThat(alternating.reason()).isEqualTo("5 flips in 5 transitions (1.00 ≥ 0.20)");
    // A pass on retry (K) is a pass for flips: PKP has none.
    assertThat(of("PKPPP").flipCount()).isZero();
    // 6 flips in 19 transitions = 0.316 (the design's example).
    Health design = of("PPPFPPPFPPPFPPPPPPPP");
    assertThat(design.observed()).isEqualTo(20);
    assertThat(design.flipCount()).isEqualTo(6);
    assertThat(design.flipRate()).isEqualByComparingTo("0.316");
  }

  @Test
  void theFlipThresholdIsInclusive() {
    // 1 flip in 5 transitions is exactly 0.2.
    assertThat(of("PPPPPF").label()).isEqualTo(Label.FLAKY);
    // 1 in 6 is below it, and the last result alone is not a failing streak.
    assertThat(of("PPPPPPF").label()).isEqualTo(Label.STABLE);
  }

  @Test
  void twoPassesOnRetryAreFlakyEvenWithoutFlips() {
    Health h = of("PPKPPPPKPP");
    assertThat(h.flipCount()).isZero();
    assertThat(h.flakyCount()).isEqualTo(2);
    assertThat(h.label()).isEqualTo(Label.FLAKY);
    assertThat(h.reason()).isEqualTo("2 passed only on a retry (≥ 2)");
    assertThat(of("PPKPPPPPPP").label()).isEqualTo(Label.STABLE);
  }

  @Test
  void threeFailuresInARowAfterAStableRunAreFailing() {
    Health h = of("PPPPPPPPPPFFT");
    assertThat(h.label()).isEqualTo(Label.FAILING);
    assertThat(h.reason()).isEqualTo("the last 3 results failed");
    assertThat(of("FFFFF").label()).as("only failures: no flips").isEqualTo(Label.FAILING);
  }

  @Test
  void aStableCaseSaysWhy() {
    Health h = of("PPPPPPPPPP");
    assertThat(h.label()).isEqualTo(Label.STABLE);
    assertThat(h.reason())
        .isEqualTo("0 flips in 9 transitions (0.00 < 0.20), 0 passed only on a retry");
  }

  @Test
  void appendKeepsOnlyTheNewestWindow() {
    String window = "";
    for (int i = 0; i < 25; i++) {
      window = CaseHealthWindow.append(window, i % 5 == 0 ? 'F' : 'P', 20);
    }
    assertThat(window).isEqualTo("FPPPPFPPPPFPPPPFPPPP");
    assertThat(CaseHealthWindow.letter(CaseStatus.SKIPPED)).isNull();
    assertThat(CaseHealthWindow.letter(CaseStatus.FLAKY)).isEqualTo('K');
    assertThatThrownBy(() -> CaseHealthWindow.append("", 'S', 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theThresholdsAreConfigurable() {
    CaseHealthProperties strict = new CaseHealthProperties(10, 3, 0.5, 3, 2);
    assertThat(CaseHealthWindow.of("PPF", strict).label()).isEqualTo(Label.FLAKY); // 1/2 ≥ 0.5
    assertThat(CaseHealthWindow.of("PPPPFF", strict).label()).isEqualTo(Label.FAILING);
    assertThat(CaseHealthWindow.of("PPPPPPPPPPPP", strict).observed()).isEqualTo(10);
    assertThatThrownBy(() -> new CaseHealthProperties(4, 5, 0.2, 2, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CaseHealthProperties(20, 5, 0, 2, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A deliberately naive second implementation of FR-4 to compare against. */
  private static Label reference(List<Character> all) {
    List<Character> w = all.subList(Math.max(0, all.size() - 20), all.size());
    int n = w.size();
    if (n < 5) {
      return Label.INSUFFICIENT_DATA;
    }
    boolean anyPass = false;
    boolean anyFail = false;
    int flips = 0;
    int retries = 0;
    for (int i = 0; i < n; i++) {
      boolean pass = w.get(i) == 'P' || w.get(i) == 'K';
      anyPass |= pass;
      anyFail |= !pass;
      retries += w.get(i) == 'K' ? 1 : 0;
      if (i > 0 && pass != (w.get(i - 1) == 'P' || w.get(i - 1) == 'K')) {
        flips++;
      }
    }
    if ((anyPass && anyFail && flips * 5 >= n - 1) || retries >= 2) {
      return Label.FLAKY;
    }
    boolean lastThreeFailed = true;
    for (int i = n - 3; i < n; i++) {
      lastThreeFailed &= !(w.get(i) == 'P' || w.get(i) == 'K');
    }
    return lastThreeFailed ? Label.FAILING : Label.STABLE;
  }

  @Test
  void incrementalAppendsAgreeWithTheReferenceOnRandomHistories() {
    Random random = new Random(2026);
    char[] letters = {'P', 'P', 'P', 'K', 'F', 'T'};
    for (int trial = 0; trial < 5_000; trial++) {
      List<Character> history = new ArrayList<>();
      String window = "";
      int length = random.nextInt(60);
      for (int i = 0; i < length; i++) {
        char next = letters[random.nextInt(letters.length)];
        history.add(next);
        window = CaseHealthWindow.append(window, next, 20);
        Health h = CaseHealthWindow.of(window, DEFAULTS);
        assertThat(h.label()).as("history %s", history).isEqualTo(reference(history));
        assertThat(h.passCount() + h.failCount()).isEqualTo(h.observed());
        assertThat(h.observed()).isEqualTo(Math.min(history.size(), 20));
      }
    }
  }
}
