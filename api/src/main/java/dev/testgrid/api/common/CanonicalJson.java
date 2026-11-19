package dev.testgrid.api.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical JSON for hashing (contracts §4 request hash, §10 suite revision): object keys sorted by
 * UTF-16 code unit (the order of JavaScript's default sort), array order kept, no whitespace,
 * integers only. Another implementation (the PRD-03 worker) must produce identical bytes.
 */
public final class CanonicalJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private CanonicalJson() {}

  public static String write(JsonNode node) {
    return MAPPER.writeValueAsString(sorted(node));
  }

  private static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(Map.Entry.comparingByKey());
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<String, JsonNode> e : entries) {
        out.set(e.getKey(), sorted(e.getValue()));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode item : node) {
        out.add(sorted(item));
      }
      return out;
    }
    if (node.isNumber() && !node.isIntegralNumber()) {
      // Decimal formatting differs between Java and JavaScript, so floats have no canonical form.
      throw new IllegalArgumentException("canonical JSON does not allow non-integer numbers");
    }
    return node;
  }
}
