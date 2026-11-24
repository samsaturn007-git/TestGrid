package dev.testgrid.api.compare;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.compare.RunComparison.CaseOutcome;
import dev.testgrid.api.compare.RunComparison.Category;
import dev.testgrid.api.compare.RunComparison.EngineSide;
import dev.testgrid.api.compare.RunComparison.Key;
import dev.testgrid.api.compare.RunComparison.Result;
import dev.testgrid.api.compare.RunComparison.Row;
import dev.testgrid.api.compare.RunComparison.Side;
import dev.testgrid.api.compare.RunComparison.Thresholds;
import dev.testgrid.api.domain.CaseStatus;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.TargetStatus;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RunComparisonTest {

  static final Set<String> CHECKOUT =
      Set.of(
          "add-to-cart",
          "apply-coupon",
          "browse-catalog",
          "card-declined",
          "cart-total",
          "checkout-happy-path");
  static final Thresholds DEFAULT = new Thresholds(1.5, 500);

  private static CaseOutcome pass(int ms) {
    return new CaseOutcome(CaseStatus.PASSED, ms, null);
  }

  private static CaseOutcome fail(UUID fingerprint) {
    return new CaseOutcome(CaseStatus.FAILED, 900, fingerprint);
  }

  /** Every case passes in {@code ms} on every engine, except the overrides. */
  private static Side side(
      Set<String> cases, Map<String, CaseOutcome> overrides, Engine... engines) {
    Map<Engine, EngineSide> map = new EnumMap<>(Engine.class);
    for (Engine e : engines) {
      Map<String, CaseOutcome> results = new HashMap<>();
      cases.forEach(c -> results.put(c, overrides.getOrDefault(c, pass(1000))));
      boolean failed = results.values().stream().anyMatch(o -> o.status() == CaseStatus.FAILED);
      map.put(e, new EngineSide(failed ? TargetStatus.FAILED : TargetStatus.PASSED, results));
    }
    return new Side(cases, map);
  }

  private static Result compare(Side base, Side head) {
    return RunComparison.compare(base, head, Map.of(), DEFAULT);
  }

  private static Row row(Result r, String caseKey, Engine engine) {
    return r.rows().stream()
        .filter(x -> x.caseKey().equals(caseKey) && x.engine() == engine)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void cmp001CartTotalIsANewFailureOnEveryEngineAndEverythingElseStillPasses() {
    Side base = side(CHECKOUT, Map.of(), Engine.values());
    Side head = side(CHECKOUT, Map.of("cart-total", fail(UUID.randomUUID())), Engine.values());
    Result r = compare(base, head);
    assertThat(r.rows()).hasSize(18);
    assertThat(r.rows().stream().filter(x -> x.category() == Category.NEW_FAILURE))
        .extracting(Row::caseKey, Row::engine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("cart-total", Engine.CHROMIUM),
            org.assertj.core.groups.Tuple.tuple("cart-total", Engine.FIREFOX),
            org.assertj.core.groups.Tuple.tuple("cart-total", Engine.WEBKIT));
    assertThat(r.counts().get(Category.NEW_FAILURE)).isEqualTo(3);
    assertThat(r.counts().get(Category.STILL_PASSING)).isEqualTo(15);
    assertThat(r.counts()).hasSize(Category.values().length);
    // Display order: NEW_FAILURE rows first.
    assertThat(r.rows().getFirst().category()).isEqualTo(Category.NEW_FAILURE);
  }

  @Test
  void cmp002ACaseTwiceAsSlowIsARegressionOnlyPastBothThresholds() {
    Side base =
        side(
            CHECKOUT,
            Map.of(
                "cart-total",
                pass(1000),
                "add-to-cart",
                pass(200),
                "apply-coupon",
                pass(2000),
                "browse-catalog",
                pass(1000)),
            Engine.CHROMIUM);
    Side head =
        side(
            CHECKOUT,
            Map.of(
                "cart-total",
                pass(2000),
                "add-to-cart",
                pass(400),
                "apply-coupon",
                pass(3000),
                "browse-catalog",
                pass(1501)),
            Engine.CHROMIUM);
    Result r = compare(base, head);
    assertThat(row(r, "cart-total", Engine.CHROMIUM).durationRegression())
        .as("2× and +1000 ms")
        .isTrue();
    assertThat(row(r, "add-to-cart", Engine.CHROMIUM).durationRegression())
        .as("2× but only +200 ms")
        .isFalse();
    assertThat(row(r, "apply-coupon", Engine.CHROMIUM).durationRegression())
        .as("exactly 1.5×")
        .isFalse();
    assertThat(row(r, "browse-catalog", Engine.CHROMIUM).durationRegression())
        .as("1.501× and +501 ms")
        .isTrue();
    assertThat(row(r, "cart-total", Engine.CHROMIUM).category()).isEqualTo(Category.STILL_PASSING);
    assertThat(RunComparison.compare(base, head, Map.of(), new Thresholds(3, 0)).rows())
        .noneMatch(Row::durationRegression);
  }

  @Test
  void fixedAndStillFailingWithAndWithoutANewSignature() {
    UUID same = UUID.randomUUID();
    Side base =
        side(
            CHECKOUT,
            Map.of(
                "cart-total",
                fail(same),
                "card-declined",
                fail(same),
                "apply-coupon",
                fail(UUID.randomUUID())),
            Engine.CHROMIUM);
    Side head =
        side(
            CHECKOUT,
            Map.of("cart-total", fail(same), "card-declined", fail(UUID.randomUUID())),
            Engine.CHROMIUM);
    Result r = compare(base, head);
    assertThat(row(r, "apply-coupon", Engine.CHROMIUM).category()).isEqualTo(Category.FIXED);
    Row unchanged = row(r, "cart-total", Engine.CHROMIUM);
    assertThat(unchanged.category()).isEqualTo(Category.STILL_FAILING);
    assertThat(unchanged.fingerprintChanged()).isFalse();
    Row changed = row(r, "card-declined", Engine.CHROMIUM);
    assertThat(changed.category()).isEqualTo(Category.STILL_FAILING);
    assertThat(changed.fingerprintChanged()).isTrue();
    assertThat(changed.reason()).isEqualTo("the failure signature changed");
  }

  @Test
  void flakyInHeadComesFromAPassOnRetryOrAFlakyHistory() {
    Side base = side(CHECKOUT, Map.of(), Engine.CHROMIUM, Engine.FIREFOX);
    Side head =
        side(
            CHECKOUT,
            Map.of("checkout-happy-path", new CaseOutcome(CaseStatus.FLAKY, 2400, null)),
            Engine.CHROMIUM,
            Engine.FIREFOX);
    Result r =
        RunComparison.compare(
            base,
            head,
            Map.of(
                new Key("card-declined", Engine.FIREFOX),
                "FLAKY",
                new Key("cart-total", Engine.FIREFOX),
                "STABLE"),
            DEFAULT);
    assertThat(row(r, "checkout-happy-path", Engine.CHROMIUM).category())
        .isEqualTo(Category.FLAKY_IN_HEAD);
    assertThat(row(r, "checkout-happy-path", Engine.CHROMIUM).reason())
        .isEqualTo("passed only on a retry in head");
    Row history = row(r, "card-declined", Engine.FIREFOX);
    assertThat(history.category()).isEqualTo(Category.FLAKY_IN_HEAD);
    assertThat(history.reason()).isEqualTo("its history on firefox is labelled FLAKY");
    assertThat(history.headHealthLabel()).isEqualTo("FLAKY");
    assertThat(row(r, "cart-total", Engine.FIREFOX).category()).isEqualTo(Category.STILL_PASSING);
  }

  @Test
  void addedAndRemovedCasesOnlyForEnginesThatRan() {
    Set<String> baseCases = Set.of("cart-total", "old-case");
    Set<String> headCases = Set.of("cart-total", "new-case");
    Side base = side(baseCases, Map.of(), Engine.CHROMIUM);
    Side head = side(headCases, Map.of(), Engine.CHROMIUM, Engine.FIREFOX);
    Result r = compare(base, head);
    assertThat(r.rows().stream().filter(x -> x.category() == Category.ADDED))
        .extracting(Row::caseKey, Row::engine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("new-case", Engine.CHROMIUM),
            org.assertj.core.groups.Tuple.tuple("new-case", Engine.FIREFOX));
    assertThat(r.rows().stream().filter(x -> x.category() == Category.REMOVED))
        .extracting(Row::caseKey, Row::engine)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("old-case", Engine.CHROMIUM));
    Row noBase = row(r, "cart-total", Engine.FIREFOX);
    assertThat(noBase.category()).isEqualTo(Category.NOT_COMPARABLE);
    assertThat(noBase.reason()).isEqualTo("no firefox target in the base run");
  }

  @Test
  void anInfraErrorTargetOrASkippedCaseIsNotComparable() {
    Side base =
        side(
            CHECKOUT,
            Map.of("cart-total", new CaseOutcome(CaseStatus.SKIPPED, 0, null)),
            Engine.CHROMIUM,
            Engine.FIREFOX);
    Map<Engine, EngineSide> engines =
        new EnumMap<>(side(CHECKOUT, Map.of(), Engine.CHROMIUM).engines());
    engines.put(Engine.FIREFOX, new EngineSide(TargetStatus.INFRA_ERROR, null));
    Side head = new Side(CHECKOUT, engines);
    Result r = compare(base, head);
    assertThat(r.rows().stream().filter(x -> x.engine() == Engine.FIREFOX))
        .allSatisfy(x -> {
          assertThat(x.category()).isEqualTo(Category.NOT_COMPARABLE);
          assertThat(x.reason())
              .isIn(
                  "the head target is INFRA_ERROR with no case results", "skipped in the base run");
        });
    assertThat(row(r, "cart-total", Engine.CHROMIUM).reason()).isEqualTo("skipped in the base run");
    assertThat(row(r, "cart-total", Engine.CHROMIUM).durationRegression()).isFalse();
  }

  @Test
  void largeRunsCompareKeyByKey() {
    Set<String> cases =
        IntStream.range(0, 5_000).mapToObj(i -> "case-" + i).collect(Collectors.toSet());
    Side base = side(cases, Map.of(), Engine.values());
    Side head = side(cases, Map.of("case-42", fail(null)), Engine.values());
    Result r = compare(base, head);
    assertThat(r.rows()).hasSize(15_000);
    assertThat(r.counts().get(Category.NEW_FAILURE)).isEqualTo(3);
    assertThat(List.copyOf(r.counts().keySet())).containsExactly(Category.values());
  }
}
