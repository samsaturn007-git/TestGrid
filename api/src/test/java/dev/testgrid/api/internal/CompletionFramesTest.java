package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** PRD-07 FR-1: the optional {@code errorFrames} on a case result (CONFLICT_REGISTER N24). */
class CompletionFramesTest extends WorkerApiTestBase {

  private static String failedCartTotal(String frames) {
    return "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":10,\"retryCount\":0,"
        + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"x\",\"errorFrames\":"
        + frames
        + "}";
  }

  @Test
  void aFailureMayCarryUpToThreeFrames() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    String frames =
        "[\"suites/support/cart.ts:addItem\",\"suites/checkout-smoke.spec.ts:<anonymous>\","
            + "\"suites/checkout-smoke.spec.ts:Object.<anonymous>\"]";
    var response =
        complete(
            claim,
            "k-frames",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of("cart-total", failedCartTotal(frames))));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  @Test
  void nullFramesAreTheSameAsNone() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    var response =
        complete(
            claim,
            "k-null",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of("cart-total", failedCartTotal("null"))));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  @Test
  void framesOnAPassingCaseAre422() {
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    String passWithFrames =
        "{\"caseKey\":\"cart-total\",\"status\":\"PASSED\",\"durationMs\":10,\"retryCount\":0,"
            + "\"errorClass\":null,\"errorMessage\":null,"
            + "\"errorFrames\":[\"suites/checkout-smoke.spec.ts:<anonymous>\"]}";
    var response =
        complete(
            claim,
            "k-pass",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of("cart-total", passWithFrames)));
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.body()).contains("/problems/invalid-completion");
    assertThat(count("SELECT count(*) FROM case_results")).isZero();
  }

  @Test
  void framesThatAreNotFileFunctionAre400() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    start(claim);
    Map<String, String> bad =
        Map.of(
            "four frames", "[\"a.ts:f\",\"b.ts:g\",\"c.ts:h\",\"d.ts:i\"]",
            "a line number", "[\"suites/checkout-smoke.spec.ts:53\"]",
            "an absolute path", "[\"/repo/worker/suites/a.ts:f\"]",
            "traversal", "[\"../../etc/passwd:f\"]",
            "a separator", "[\"a.ts:f|g\"]",
            "a null frame", "[null]");
    bad.forEach((why, frames) -> {
      var response =
          complete(
              claim,
              "k-" + why.hashCode(),
              "COMPLETED_WITH_RESULTS",
              checkoutResults(Map.of("cart-total", failedCartTotal(frames))));
      assertThat(response.statusCode()).as(why + ": " + response.body()).isEqualTo(400);
    });
    assertThat(count("SELECT count(*) FROM case_results")).isZero();
  }
}
