package dev.testgrid.api.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.ErrorClass;
import dev.testgrid.api.failure.FingerprintV1.Fingerprint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FingerprintV1Test {

  private static final String ESC = "\u001b";
  private static final List<String> SPEC_FRAME =
      List.of("suites/checkout-smoke.spec.ts:<anonymous>");

  /** PRODUCT_DEFECT as Playwright 1.65.1 reports it (worker/test/fixtures), with its colours. */
  private static String productDefect(String received, int resolvedTimes) {
    return "Error: "
        + ESC
        + "[2mexpect("
        + ESC
        + "[22m"
        + ESC
        + "[31mlocator"
        + ESC
        + "[39m"
        + ESC
        + "[2m).toHaveText(expected) failed\n\n"
        + "Locator:  getByTestId('cart-total')\n"
        + "Expected: \"$80.99\"\n"
        + "Received: \""
        + received
        + "\"\n"
        + "Timeout:  5000ms\n\n"
        + "Call log:\n"
        + "  - Expect \"toHaveText\" getByTestId('cart-total') with timeout 5000ms\n"
        + "  - waiting for getByTestId('cart-total')\n"
        + "    "
        + resolvedTimes
        + " × locator resolved to <dd data-testid=\"cart-total\">…</dd>\n"
        + "      - unexpected value \""
        + received
        + "\"";
  }

  @Test
  void replacesEachVolatileTokenWithItsPlaceholder() {
    assertThat(FingerprintV1.normalise("order 10234 of 3 items"))
        .isEqualTo("order <N> of <N> items");
    assertThat(FingerprintV1.normalise("run 355f5da2-13e5-4627-9809-3d63fc40533e failed"))
        .isEqualTo("run <UUID> failed");
    assertThat(FingerprintV1.normalise("at 2026-11-24T08:25:06.783Z and 2026-11-24 08:25+05:30"))
        .isEqualTo("at <TS> and <TS>");
    assertThat(FingerprintV1.normalise("logged 13:55:01.767 then")).isEqualTo("logged <TS> then");
    assertThat(FingerprintV1.normalise("pointer 0x7ffde4a1 commit 8b21e4d0c9 ok"))
        .isEqualTo("pointer <HEX> commit <HEX> ok");
    assertThat(FingerprintV1.normalise("GET http://demo-shop:4173/cart?session=abc&seed=42#top"))
        .isEqualTo("GET http://demo-shop:<N>/cart#top");
    assertThat(FingerprintV1.normalise("wrote /tmp/tg-attempt-x1/out.zip and /var/folders/q/T/a"))
        .isEqualTo("wrote <TMP> and <TMP>");
    assertThat(FingerprintV1.normalise("see test-results/checkout-cart-total-retry1/trace.zip"))
        .isEqualTo("see <TMP>");
    assertThat(FingerprintV1.normalise("C:\\Users\\ci\\AppData\\Local\\Temp\\pw-3\\x.png"))
        .isEqualTo("<TMP>");
  }

  @Test
  void stripsColoursControlCharactersTheCallLogAndExtraSpace() {
    assertThat(
            FingerprintV1.normalise(ESC + "[31mTest timeout of 20000ms exceeded." + ESC + "[39m"))
        .isEqualTo("Test timeout of <N>ms exceeded.");
    assertThat(FingerprintV1.normalise("a\u0000b\r\n\t  c  \n\n \n d ")).isEqualTo("ab\nc\nd");
    assertThat(FingerprintV1.normalise("TimeoutError: locator.click\nCall log:\n  - waiting 12"))
        .isEqualTo("TimeoutError: locator.click");
    assertThat(FingerprintV1.normalise(null)).isEmpty();
  }

  @Test
  void keepsWordsThatOnlyLookLikeHex() {
    // Eight or more hex letters without a digit are ordinary words, not identifiers.
    assertThat(FingerprintV1.normalise("deadbeefcafe facade accede"))
        .isEqualTo("deadbeefcafe facade accede");
  }

  @Test
  void int001TheSameSeededFailureHasOneFingerprintAcrossRuns() {
    List<String> fiveRuns =
        List.of(
            productDefect("$82.99", 14),
            productDefect("$83.49", 9),
            productDefect("$82.99", 31),
            productDefect("$182.10", 2),
            // As stored: the worker already stripped the colours.
            FingerprintV1.stripAnsi(productDefect("$82.99", 14)));
    Set<String> fingerprints = new HashSet<>();
    for (String message : fiveRuns) {
      fingerprints.add(FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, message).value());
    }
    assertThat(fingerprints).hasSize(1);
    assertThat(
            FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, fiveRuns.getFirst())
                .normalisedMessage())
        .isEqualTo(
            "Error: expect(locator).toHaveText(expected) failed\n"
                + "Locator: getByTestId('cart-total')\n"
                + "Expected: \"$<N>.<N>\"\n"
                + "Received: \"$<N>.<N>\"\n"
                + "Timeout: <N>ms");
  }

  @Test
  void int001OrderNumbersTimestampsIdsAndPathsDoNotSplitAGroup() {
    String template = "order %d placed %s for run %s, trace %s, page http://demo-shop:4173/o?id=%d";
    Fingerprint a =
        FingerprintV1.of(
            ErrorClass.ASSERTION,
            SPEC_FRAME,
            template.formatted(
                10234,
                "2026-11-24T08:25:06.783Z",
                "355f5da2-13e5-4627-9809-3d63fc40533e",
                "/tmp/tg-1/a.zip",
                10234));
    Fingerprint b =
        FingerprintV1.of(
            ErrorClass.ASSERTION,
            SPEC_FRAME,
            template.formatted(
                77,
                "2026-12-01T23:59:59Z",
                "f2b04b82-2438-447b-a9d6-bb2969c56858",
                "/tmp/tg-99/b.zip",
                77));
    assertThat(a.value()).isEqualTo(b.value());
  }

  @Test
  void int002DifferentFixtureFailuresGetDifferentFingerprints() {
    Map<String, Fingerprint> byScenario = new HashMap<>();
    byScenario.put(
        "PRODUCT_DEFECT",
        FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, productDefect("$82.99", 14)));
    byScenario.put(
        "AUTOMATION_FLAKE",
        FingerprintV1.of(
            ErrorClass.ASSERTION,
            SPEC_FRAME,
            "Error: expect(locator).toBeVisible() failed\n\nLocator: getByTestId('order-confirmation-banner')"
                + "\nExpected: visible\nTimeout: 5000ms\nError: element(s) not found\n\nCall log:\n  - x"));
    byScenario.put(
        "SELECTOR_DRIFT",
        FingerprintV1.of(
            ErrorClass.SELECTOR_NOT_FOUND,
            SPEC_FRAME,
            "TimeoutError: locator.click: Timeout 5000ms exceeded.\nCall log:\n"
                + "  - waiting for getByTestId('checkout-button')\n"));
    byScenario.put(
        "TIMEOUT",
        FingerprintV1.of(ErrorClass.TEST_TIMEOUT, List.of(), "Test timeout of 20000ms exceeded."));
    byScenario.put(
        "BROWSER_LAUNCH",
        FingerprintV1.of(
            ErrorClass.BROWSER_LAUNCH,
            List.of(),
            "browserType.launch: Executable doesn't exist at /ms-playwright/firefox-1497/firefox/firefox"));
    assertThat(byScenario.values().stream().map(Fingerprint::value).distinct()).hasSize(5);
  }

  @Test
  void theErrorClassAndEveryFrameArePartOfTheFingerprint() {
    String message = "Error: expect(locator).toBeVisible() failed";
    String base = FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, message).value();
    assertThat(FingerprintV1.of(ErrorClass.UNKNOWN, SPEC_FRAME, message).value())
        .isNotEqualTo(base);
    assertThat(FingerprintV1.of(ErrorClass.ASSERTION, List.of(), message).value())
        .isNotEqualTo(base);
    List<String> two =
        List.of("suites/support/cart.ts:addItem", "suites/checkout-smoke.spec.ts:<anonymous>");
    assertThat(FingerprintV1.of(ErrorClass.ASSERTION, two, message).value())
        .isNotEqualTo(FingerprintV1.of(ErrorClass.ASSERTION, two.reversed(), message).value());
  }

  @Test
  void aFrameCanNotMoveTheBoundaryIntoTheMessage() {
    // Without separators these two would hash the same string.
    Fingerprint withFrame = FingerprintV1.of(ErrorClass.ASSERTION, List.of("a.ts:f"), "x");
    Fingerprint inMessage = FingerprintV1.of(ErrorClass.ASSERTION, List.of(), "a.ts:f|x");
    assertThat(withFrame.value()).isNotEqualTo(inMessage.value());
  }

  @Test
  void onlyTheTopThreeFramesCount() {
    List<String> three = List.of("a.ts:f", "b.ts:g", "c.ts:h");
    List<String> four = List.of("a.ts:f", "b.ts:g", "c.ts:h", "d.ts:i");
    assertThat(FingerprintV1.of(ErrorClass.ASSERTION, four, "m").value())
        .isEqualTo(FingerprintV1.of(ErrorClass.ASSERTION, three, "m").value());
  }

  @Test
  void refusesFramesWithLineNumbersAbsolutePathsTraversalOrSeparators() {
    for (String bad :
        List.of(
            "suites/checkout-smoke.spec.ts:53",
            "/repo/worker/suites/a.ts:f",
            "../etc/passwd:f",
            "suites/../../x.ts:f",
            "a.ts:f|g",
            "a.ts:f;b.ts:g",
            "a.ts",
            "a.ts:f g",
            "")) {
      assertThatThrownBy(() -> FingerprintV1.of(ErrorClass.ASSERTION, List.of(bad), "m"))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat("suites/support/fixture-env.ts:Object.<anonymous>").matches(FingerprintV1.FRAME);
    assertThat("suites/v2..final.ts:f").matches(FingerprintV1.FRAME);
  }

  @Test
  void fpV1IsPinnedSoItNeverChangesSilently() {
    // Stored fingerprints are compared with new ones: changing the algorithm means fp_v2.
    Fingerprint pinned =
        FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, productDefect("$82.99", 14));
    assertThat(pinned.algoVersion()).isEqualTo("fp_v1");
    assertThat(pinned.value()).isEqualTo("31e12582bd8cb543").matches("[0-9a-f]{16}");
  }

  @Test
  void noCollisionsAmongManyDistinctFailures() {
    // 64 bits: P(any collision among 200,000) ≈ n²/2⁶⁵ ≈ 1e-9. Letters g–z never normalise.
    Random random = new Random(7);
    Set<String> messages = new HashSet<>();
    while (messages.size() < 200_000) {
      StringBuilder word = new StringBuilder();
      for (int i = 0; i < 12; i++) {
        word.append((char) ('g' + random.nextInt(20)));
      }
      messages.add("expect(" + word + ") failed");
    }
    Set<String> fingerprints = new HashSet<>();
    for (String message : messages) {
      fingerprints.add(FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, message).value());
    }
    assertThat(fingerprints).hasSize(messages.size());
  }

  private static final List<String> FRAGMENTS =
      List.of(
          "0",
          "7",
          "42",
          "0x",
          "0xff",
          "deadbeef1",
          "abcdefab",
          "-",
          ":",
          ".",
          ",",
          "?",
          "#",
          "/",
          "\\",
          "<",
          ">",
          "\"",
          "'",
          " ",
          "  ",
          "\t",
          "\n",
          "\r",
          "\u0000",
          ESC,
          ESC + "[31m",
          ESC + "[",
          "m",
          "[2m",
          ESC + "]8;;",
          "\u0007",
          "Call log:",
          "\nCall log:",
          "http://",
          "https://h:4173/p",
          "?q=1&r=2",
          "/tmp/",
          "/var/folders/",
          "/private",
          "test-results/",
          "C:\\",
          "Temp\\",
          "T",
          "Z",
          "+05:30",
          "2026-11-24",
          "08:25",
          "08:25:06.783",
          "355f5da2-13e5-4627-9809-3d63fc40533e",
          "abcdefab-abcd-abcd-abcd-abcdefabcdef",
          "<N>",
          "<UUID>",
          "<HEX>",
          "<TS>",
          "<TMP>",
          "x",
          "Error",
          "×",
          "…",
          "é");

  @Test
  void normalisationIsIdempotent() {
    // Property n(n(x)) = n(x) over 20,000 seeded inputs built from the fragments each rule
    // reacts to, in random order (a fixed seed keeps it deterministic; CONFLICT_REGISTER N28).
    Random random = new Random(20261124);
    List<String> counterexamples = new ArrayList<>();
    IntStream.range(0, 20_000)
        .forEach(i -> {
          StringBuilder input = new StringBuilder();
          int parts = 1 + random.nextInt(40);
          for (int p = 0; p < parts; p++) {
            input.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
          }
          String once = FingerprintV1.normalise(input.toString());
          if (!FingerprintV1.normalise(once).equals(once)) {
            counterexamples.add(input.toString());
          }
        });
    assertThat(counterexamples).isEmpty();
  }

  @Test
  void normalisingAgainDoesNotChangeTheFingerprint() {
    String message = productDefect("$82.99", 14);
    Fingerprint first = FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, message);
    Fingerprint again =
        FingerprintV1.of(ErrorClass.ASSERTION, SPEC_FRAME, first.normalisedMessage());
    assertThat(again.value()).isEqualTo(first.value());
  }
}
