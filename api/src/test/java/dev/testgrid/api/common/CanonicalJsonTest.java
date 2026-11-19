package dev.testgrid.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalJsonTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void sortsKeysAtEveryLevelAndKeepsArrayOrder() {
    var node =
        mapper.readTree(
            "{ \"b\": [3, 1, {\"z\": 1, \"a\": null}], \"a\": {\"y\": true, \"x\": \"é/\\n\"} }");
    assertThat(CanonicalJson.write(node))
        .isEqualTo("{\"a\":{\"x\":\"é/\\n\",\"y\":true},\"b\":[3,1,{\"a\":null,\"z\":1}]}");
  }

  @Test
  void formattingAndKeyOrderDoNotChangeTheOutput() {
    var compact = mapper.readTree("{\"k\":1,\"j\":[\"x\"]}");
    var pretty = mapper.readTree("{\n  \"j\" : [ \"x\" ],\n  \"k\" : 1\n}");
    assertThat(CanonicalJson.write(compact)).isEqualTo(CanonicalJson.write(pretty));
  }

  @Test
  void rejectsNonIntegerNumbers() {
    var node = mapper.readTree("{\"ratio\": 1.5}");
    assertThatThrownBy(() -> CanonicalJson.write(node))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-integer");
  }
}
