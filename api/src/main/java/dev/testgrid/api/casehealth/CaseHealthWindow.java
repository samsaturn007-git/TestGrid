package dev.testgrid.api.casehealth;

import dev.testgrid.api.domain.CaseStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PRD-07 FR-4: a case's health from its last N outcomes on one engine. Pure, so the incremental
 * update in the completion transaction and a recount from history give the same answer.
 *
 * <p>The window is a string of one letter per outcome, oldest first: {@code P} passed, {@code K}
 * passed only on a Playwright retry (FLAKY), {@code F} failed, {@code T} timed out. SKIPPED says
 * nothing about health and is never recorded. Appending is O(N) for a window of N ≤ 100.
 */
public final class CaseHealthWindow {

  public enum Label {
    STABLE,
    FLAKY,
    FAILING,
    INSUFFICIENT_DATA
  }

  public record Health(
      int windowSize,
      String outcomes,
      int observed,
      int passCount,
      int failCount,
      int flakyCount,
      int flipCount,
      BigDecimal flipRate,
      Label label,
      String reason) {}

  private CaseHealthWindow() {}

  /** The window letter for a case status, or {@code null} for SKIPPED. */
  public static Character letter(CaseStatus status) {
    return switch (status) {
      case PASSED -> 'P';
      case FLAKY -> 'K';
      case FAILED -> 'F';
      case TIMED_OUT -> 'T';
      case SKIPPED -> null;
    };
  }

  static boolean passed(char outcome) {
    return outcome == 'P' || outcome == 'K';
  }

  /** Adds the newest outcome and keeps the last {@code windowSize}. */
  public static String append(String outcomes, char outcome, int windowSize) {
    if ("PKFT".indexOf(outcome) < 0) {
      throw new IllegalArgumentException("not a window outcome: " + outcome);
    }
    String next = outcomes + outcome;
    return next.length() <= windowSize ? next : next.substring(next.length() - windowSize);
  }

  public static Health of(String window, CaseHealthProperties p) {
    String outcomes =
        window.length() <= p.windowSize()
            ? window
            : window.substring(window.length() - p.windowSize());
    int n = outcomes.length();
    int passes = 0;
    int flaky = 0;
    int flips = 0;
    for (int i = 0; i < n; i++) {
      char o = outcomes.charAt(i);
      if (!(o == 'P' || o == 'K' || o == 'F' || o == 'T')) {
        throw new IllegalArgumentException("not a window outcome: " + o);
      }
      passes += passed(o) ? 1 : 0;
      flaky += o == 'K' ? 1 : 0;
      if (i > 0 && passed(o) != passed(outcomes.charAt(i - 1))) {
        flips++;
      }
    }
    int fails = n - passes;
    // flip_rate = transitions / (n − 1): how often consecutive results disagree.
    BigDecimal rate =
        n < 2
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(flips).divide(BigDecimal.valueOf(n - 1), 10, RoundingMode.HALF_UP);
    BigDecimal threshold = BigDecimal.valueOf(p.flipRateThreshold());
    BigDecimal shown = rate.setScale(3, RoundingMode.HALF_UP);
    String transitions =
        flips + (flips == 1 ? " flip" : " flips") + " in " + Math.max(0, n - 1) + " transitions";

    Label label;
    String reason;
    boolean bothOutcomes = passes > 0 && fails > 0;
    boolean flipping = bothOutcomes && rate.compareTo(threshold) >= 0;
    boolean retries = flaky >= p.flakyStatuses();
    boolean streak =
        n >= p.failingStreak()
            && outcomes.substring(n - p.failingStreak()).chars().noneMatch(c -> passed((char) c));
    if (n < p.minObservations()) {
      label = Label.INSUFFICIENT_DATA;
      reason =
          n + (n == 1 ? " result" : " results") + "; a label needs at least " + p.minObservations();
    } else if (flipping || retries) {
      label = Label.FLAKY;
      String flipsPart =
          transitions
              + " ("
              + shown.setScale(2, RoundingMode.HALF_UP)
              + (flipping ? " ≥ " : " < ")
              + threshold.setScale(2, RoundingMode.HALF_UP)
              + ")";
      String retryPart =
          flaky + " passed only on a retry" + (retries ? " (≥ " + p.flakyStatuses() + ")" : "");
      reason =
          flipping && retries ? flipsPart + "; " + retryPart : flipping ? flipsPart : retryPart;
    } else if (streak) {
      label = Label.FAILING;
      reason = "the last " + p.failingStreak() + " results failed";
    } else {
      label = Label.STABLE;
      reason =
          transitions
              + " ("
              + shown.setScale(2, RoundingMode.HALF_UP)
              + " < "
              + threshold.setScale(2, RoundingMode.HALF_UP)
              + "), "
              + flaky
              + " passed only on a retry";
    }
    return new Health(
        p.windowSize(), outcomes, n, passes, fails, flaky, flips, shown, label, reason);
  }
}
