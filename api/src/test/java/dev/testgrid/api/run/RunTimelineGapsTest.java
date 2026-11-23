package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PRD-06 FR-7: a gap is a silence longer than 2 × the heartbeat interval. */
class RunTimelineGapsTest {

  static final Instant T0 = Instant.parse("2026-11-23T12:00:00Z");

  static Instant at(double seconds) {
    return T0.plusMillis((long) (seconds * 1000));
  }

  @Test
  void regularHeartbeatsHaveNoGap() {
    var gaps =
        RunTimelineService.gaps(
            at(0), at(0.4), List.of(at(10.4), at(20.4), at(30.4)), at(35), 20_000);
    assertThat(gaps).isEmpty();
  }

  @Test
  void aKilledWorkersSilenceUntilTheReaperIsOneGap() {
    // Last heartbeat at 10.4 s, reaper abandons at 45 s: 34.6 s of silence.
    var gaps = RunTimelineService.gaps(at(0), at(0.4), List.of(at(10.4)), at(45), 20_000);
    assertThat(gaps).containsExactly(new RunTimeline.Gap(at(10.4), at(45), 34_600));
  }

  @Test
  void aPauseInTheMiddleIsFoundToo() {
    var gaps =
        RunTimelineService.gaps(
            at(0), at(0.4), List.of(at(10.4), at(40.4), at(50.4)), at(52), 20_000);
    assertThat(gaps).containsExactly(new RunTimeline.Gap(at(10.4), at(40.4), 30_000));
  }

  @Test
  void exactlyTheThresholdIsNotAGapAndANeverStartedAttemptStillCounts() {
    assertThat(RunTimelineService.gaps(at(0), null, List.of(), at(20), 20_000)).isEmpty();
    assertThat(RunTimelineService.gaps(at(0), null, List.of(), at(20.001), 20_000)).hasSize(1);
  }
}
