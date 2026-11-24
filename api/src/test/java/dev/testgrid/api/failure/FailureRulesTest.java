package dev.testgrid.api.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.domain.FailureCategory;
import dev.testgrid.api.failure.FailureRules.InvalidFailureRulesException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FailureRulesTest {

  static FailureRules shipped() {
    try (InputStream in = new ClassPathResource(FailureRulesConfig.RESOURCE).getInputStream()) {
      return FailureRules.load(in);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static FailureRules parse(String yaml) {
    return FailureRules.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private static final String CONFIDENCE =
      """
      confidence:
        cap: 0.95
        floor: 0.05
        perExtraOccurrence: 0.02
        maxExtraOccurrences: 5
        disagreementPenalty: 0.30
      """;

  private static String table(String rules) {
    return "version: 1\n" + CONFIDENCE + "rules:\n" + rules;
  }

  private static final String CATCH_ALL =
      """
        - id: R9
          name: no-rule-matched
          category: UNKNOWN
          baseConfidence: 0.30
          explanation: nothing matched
          when:
            - {}
      """;

  @Test
  void theShippedTableHasThePrdRulesInOrder() {
    FailureRules rules = shipped();
    assertThat(rules.rules())
        .extracting(FailureRules.Rule::id, FailureRules.Rule::category)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("R1", FailureCategory.TEST_BROKEN),
            org.assertj.core.groups.Tuple.tuple("R2", FailureCategory.TIMEOUT),
            org.assertj.core.groups.Tuple.tuple("R3", FailureCategory.ENVIRONMENT),
            org.assertj.core.groups.Tuple.tuple("R4", FailureCategory.AUTOMATION_FLAKE),
            org.assertj.core.groups.Tuple.tuple("R5", FailureCategory.PRODUCT_SUSPECTED),
            org.assertj.core.groups.Tuple.tuple("R6", FailureCategory.UNKNOWN));
    assertThat(rules.confidence().cap()).isEqualTo(0.95);
  }

  @Test
  void int002RefusesAProductRuleThatCouldMatchAnInfrastructureFault() {
    String rules =
        """
          - id: R1
            name: bad
            category: PRODUCT_SUSPECTED
            baseConfidence: 0.7
            explanation: x
            when:
              - errorClassIn: [ASSERTION, BROWSER_LAUNCH]
        """
            + CATCH_ALL;
    assertThatThrownBy(() -> parse(table(rules)))
        .isInstanceOf(InvalidFailureRulesException.class)
        .hasMessageContaining(
            "PRODUCT_SUSPECTED alternative must require errorClassIn [ASSERTION]");
    String unconditional =
        """
          - id: R1
            name: bad
            category: PRODUCT_SUSPECTED
            baseConfidence: 0.7
            explanation: x
            when:
              - minAttempts: 2
        """
            + CATCH_ALL;
    assertThatThrownBy(() -> parse(table(unconditional)))
        .isInstanceOf(InvalidFailureRulesException.class);
  }

  @Test
  void refusesATableWithoutACatchAllLastRule() {
    String rules =
        """
          - id: R1
            name: only
            category: TEST_BROKEN
            baseConfidence: 0.8
            explanation: x
            when:
              - errorClassIn: [SELECTOR_NOT_FOUND]
        """;
    assertThatThrownBy(() -> parse(table(rules)))
        .hasMessageContaining("the last rule must match everything");
  }

  @Test
  void refusesTyposUnknownValuesAndBadNumbers() {
    String unknownField =
        """
          - id: R1
            name: x
            category: TEST_BROKEN
            baseConfidence: 0.8
            explanation: x
            when:
              - errorClasIn: [SELECTOR_NOT_FOUND]
        """
            + CATCH_ALL;
    assertThatThrownBy(() -> parse(table(unknownField)))
        .isInstanceOf(InvalidFailureRulesException.class);
    String unknownClass =
        unknownField.replace("errorClasIn: [SELECTOR_NOT_FOUND]", "errorClassIn: [OOPS]");
    assertThatThrownBy(() -> parse(table(unknownClass)))
        .isInstanceOf(InvalidFailureRulesException.class);
    String badStatus =
        unknownField.replace("errorClasIn: [SELECTOR_NOT_FOUND]", "statusSeen: BROKEN");
    assertThatThrownBy(() -> parse(table(badStatus))).hasMessageContaining("statusSeen BROKEN");
    String tooConfident =
        unknownField.replace("errorClasIn", "errorClassIn").replace("0.8", "0.99");
    assertThatThrownBy(() -> parse(table(tooConfident))).hasMessageContaining("baseConfidence");
    String duplicate = CATCH_ALL + CATCH_ALL;
    assertThatThrownBy(() -> parse(table(duplicate))).hasMessageContaining("unique");
    assertThatThrownBy(() -> parse("version: 1\nrules: [")).hasMessageContaining("not valid YAML");
    assertThatThrownBy(() -> parse("")).isInstanceOf(InvalidFailureRulesException.class);
    assertThatThrownBy(() -> parse(table(CATCH_ALL).replace("cap: 0.95", "cap: 0.99")))
        .hasMessageContaining("cap must be at most 0.95");
  }
}
